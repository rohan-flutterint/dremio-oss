/*
 * Copyright (C) 2017-2019 Dremio Corporation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.dremio.exec.store.parquet;

import com.dremio.common.exceptions.ExecutionSetupException;
import com.dremio.exec.physical.base.OpProps;
import com.dremio.exec.physical.config.CopyIntoExtendedProperties;
import com.dremio.exec.physical.config.SimpleQueryContext;
import com.dremio.exec.physical.config.TableFunctionConfig;
import com.dremio.exec.physical.config.copyinto.CopyIntoHistoryExtendedProperties;
import com.dremio.exec.physical.config.copyinto.CopyIntoQueryProperties;
import com.dremio.exec.planner.CopyIntoTablePlanBuilderBase;
import com.dremio.exec.store.dfs.EmptySplitReaderCreator;
import com.dremio.exec.store.dfs.SplitReaderCreator;
import com.dremio.exec.store.dfs.implicit.CompositeReaderConfig;
import com.dremio.exec.store.dfs.implicit.ImplicitFilesystemColumnFinder;
import com.dremio.io.file.Path;
import com.dremio.sabot.exec.context.OperatorContext;
import com.dremio.sabot.exec.fragment.FragmentExecutionContext;
import com.dremio.sabot.exec.store.parquet.proto.ParquetProtobuf;
import com.dremio.sabot.op.scan.ScanOperator.Metric;
import com.google.common.base.Preconditions;
import java.io.FileNotFoundException;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * ParquetSplitReaderCreatorIterator implementation for COPY INTO - Used for COPY INTO 'SKIP_FILE'
 * where it constructs twice as much row group readers: one dry run and one normal run for each RG -
 * upon failure (reported by a dry run RG reader) all subsequent RG's are cancelled. Also handles
 * errors that rise during init, due to e.g. corrupt file footers - Used for copy_errors() table
 * function as well to run validation on Parquet files For this case it will only produce the dry
 * run splits.
 */
public class CopyIntoSkipParquetSplitReaderCreatorIterator
    extends ParquetSplitReaderCreatorIterator {

  private static final String DUMMY_PATH = "DREMIO-DUMMY-PATH";
  private static final org.slf4j.Logger logger =
      org.slf4j.LoggerFactory.getLogger(CopyIntoSkipParquetSplitReaderCreatorIterator.class);

  private final CopyIntoQueryProperties copyIntoQueryProperties;
  private final SimpleQueryContext copyIntoQueryContext;
  private final CopyIntoHistoryExtendedProperties copyIntoHistoryExtendedProperties;
  private boolean isCopyIntoSkip = false;
  private boolean isValidationMode = false;
  private final Set<Path> failedFilePaths = new HashSet<>();

  public CopyIntoSkipParquetSplitReaderCreatorIterator(
      FragmentExecutionContext fragmentExecContext,
      OperatorContext context,
      OpProps props,
      TableFunctionConfig config,
      boolean fromRowGroupBasedSplit,
      boolean produceFromBufferedSplits)
      throws ExecutionSetupException {
    super(
        fragmentExecContext,
        context,
        props,
        config,
        fromRowGroupBasedSplit,
        produceFromBufferedSplits);
    Optional<CopyIntoExtendedProperties> copyIntoExtendedPropertiesOptional =
        CopyIntoExtendedProperties.Util.getProperties(extendedProperties);
    if (!copyIntoExtendedPropertiesOptional.isPresent()) {
      throw new RuntimeException(
          "CopyIntoSkipParquetCoercionReader requires CopyIntoExtendedProperties");
    }
    CopyIntoExtendedProperties copyIntoExtendedProperties =
        copyIntoExtendedPropertiesOptional.get();
    this.copyIntoQueryProperties =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.COPY_INTO_QUERY_PROPERTIES,
            CopyIntoQueryProperties.class);
    if (this.copyIntoQueryProperties != null) {
      this.isCopyIntoSkip =
          CopyIntoQueryProperties.OnErrorOption.SKIP_FILE.equals(
              this.copyIntoQueryProperties.getOnErrorOption());
    }

    this.copyIntoQueryContext =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.QUERY_CONTEXT, SimpleQueryContext.class);

    this.copyIntoHistoryExtendedProperties =
        copyIntoExtendedProperties.getProperty(
            CopyIntoExtendedProperties.PropertyKey.COPY_INTO_HISTORY_PROPERTIES,
            CopyIntoHistoryExtendedProperties.class);

    if (copyIntoHistoryExtendedProperties != null) {
      // in case of validation mode, the given output schema is that of copy_errors() result schema
      // i.e. jobId, fileName, etc... now in order to simulate copying into the target table, we
      // need
      // to override the following schema dependant constructs that are used by the Parquet reader
      this.isValidationMode = true;
      this.columns =
          CopyIntoTablePlanBuilderBase.getSchemaPaths(
              copyIntoHistoryExtendedProperties.getValidatedTableSchema());
      this.realFields =
          new ImplicitFilesystemColumnFinder(
                  context.getOptions(),
                  fs,
                  columns,
                  isAccelerator,
                  ImplicitFilesystemColumnFinder.Mode.ALL_IMPLICIT_COLUMNS)
              .getRealFields();
      this.fullSchema = copyIntoHistoryExtendedProperties.getValidatedTableSchema();
      this.readerConfig =
          CompositeReaderConfig.getCompound(
              context, fullSchema, columns, config.getFunctionContext().getPartitionColumns());
    }
  }

  @Override
  protected void initSplits(SplitReaderCreator curr, int splitsAhead) {
    while (splitsAhead > 0) {
      filterRowGroupSplits();
      SplitExpansionErrorInfo splitExpansionErrorInfo = null;
      while (!rowGroupSplitIterator.hasNext()) {
        if (!sortedBlockSplitsIterator.hasNext()) {
          currentSplitInfo = null;
          return;
        }
        ParquetBlockBasedSplit blockSplit = sortedBlockSplitsIterator.next();
        try {
          expandBlockSplit(blockSplit);
        } catch (FileNotFoundException fnfe) {
          logger.error("One or more of the referred data files are absent.", fnfe);
          throw new RuntimeException(
              String.format(
                  "One or more of the referred data files are absent [%s].", fnfe.getMessage()),
              fnfe);
        } catch (Exception e) {
          splitExpansionErrorInfo =
              new SplitExpansionErrorInfo(
                  Path.getContainerSpecificRelativePath(Path.of(blockSplit.getPath())),
                  e.getMessage());
          break;
        }
      }

      if (curr == null) {
        first = createCopyIntoSkipSplitReaderCreator(splitExpansionErrorInfo);
        curr = first;
        splitsAhead--;
        filterRowGroupSplits();
      }

      // 2nd half of condition covers the use case when 2 separate files fail their init right after
      // each other
      // (during COPY INTO SKIP there's only 1 file should be present but better safe than sorry...)
      while ((rowGroupSplitIterator.hasNext() || splitExpansionErrorInfo != null)
          && splitsAhead > 0) {
        SplitReaderCreator creator = createCopyIntoSkipSplitReaderCreator(splitExpansionErrorInfo);
        curr.setNext(creator);
        curr = creator;
        splitsAhead--;
        filterRowGroupSplits();
      }
    }
  }

  @Override
  protected SplitReaderCreator constructReaderCreator(
      ParquetFilters filtersForCurrentRowGroup,
      ParquetProtobuf.ParquetDatasetSplitScanXAttr splitScanXAttr) {
    String dataFilePath =
        splitScanXAttr.getOriginalPath().isEmpty()
            ? splitScanXAttr.getPath()
            : splitScanXAttr.getOriginalPath();
    if (failedFilePaths.contains(Path.of(dataFilePath))) {
      // EARLY cancellation of the RG due to an error seen in this file in a previous RG
      context.getStats().addLongStat(Metric.NUM_READERS_SKIPPED, 1);
      return new EmptySplitReaderCreator(Path.of(DUMMY_PATH), lastInputStreamProvider);
    }
    return new CopyIntoSkipParquetSplitReaderCreator(
        autoCorrectCorruptDates,
        context,
        enableDetailedTracing,
        fs,
        numSplitsToPrefetch,
        prefetchReader,
        readInt96AsTimeStamp,
        readerConfig,
        readerFactory,
        realFields,
        supportsColocatedReads,
        trimRowGroups,
        vectorize,
        currentSplitInfo,
        tablePath,
        filtersForCurrentRowGroup,
        columns,
        fullSchema,
        formatSettings,
        icebergSchemaFields,
        pathToRowGroupsMap,
        this,
        splitScanXAttr,
        this.isIgnoreSchemaLearning(),
        isConvertedIcebergDataset,
        userDefinedSchemaSettings,
        extendedProperties);
  }

  private SplitReaderCreator createCopyIntoSkipSplitReaderCreator(
      SplitExpansionErrorInfo splitExpansionErrorInfo) {
    if (splitExpansionErrorInfo != null) {
      context.getStats().addLongStat(Metric.NUM_READERS_SKIPPED, 1);
      return new ParquetCopyIntoSkipUtils.CopyIntoSkipErrorRecordReaderCreator(
          copyIntoQueryContext,
          copyIntoQueryProperties,
          splitExpansionErrorInfo.filePath,
          splitExpansionErrorInfo.errorMessage,
          copyIntoHistoryExtendedProperties,
          isValidationMode);
    }
    return super.createSplitReaderCreator();
  }

  @Override
  public SplitReaderCreator next() {
    Preconditions.checkArgument(hasNext());
    filterIfNecessary();
    if (first == null) {
      Preconditions.checkArgument(
          !rowGroupSplitIterator.hasNext() && !sortedBlockSplitsIterator.hasNext());
      return new EmptySplitReaderCreator(null, lastInputStreamProvider);
    }
    while (first != null && failedFilePaths.contains(first.getPath())) {
      try {
        // LATE cancellation of the RG due to an error seen in this file in a previous RG, in this
        // case the reader creator was already constructed during a prefetch, so need to close it
        // too now
        first.close();
        context.getStats().addLongStat(Metric.NUM_READERS_SKIPPED, 1);
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      first = first.getNext();
    }
    if (first == null) {
      // no more prefetched row group reader creators available at this point - need to bail out
      return new EmptySplitReaderCreator(Path.of(DUMMY_PATH), lastInputStreamProvider);
    }
    SplitReaderCreator curr = first;
    first = first.getNext();
    return curr;
  }

  @Override
  protected List<ParquetProtobuf.ParquetDatasetSplitScanXAttr> createRowGroupSplitList(
      List<Integer> rowGroupNums,
      Path splitPath,
      ParquetBlockBasedSplit blockSplit,
      long fileLength,
      long fileLastModificationTime) {
    List<ParquetProtobuf.ParquetDatasetSplitScanXAttr> rowGroupSplitAttrs = new LinkedList<>();

    // Add dry run splits first
    for (int rowGroupNum : rowGroupNums) {
      rowGroupSplitAttrs.add(
          ParquetProtobuf.ParquetDatasetSplitScanXAttr.newBuilder()
              .setRowGroupIndex(rowGroupNum)
              .setPath(splitPath.toString())
              .setStart(0L)
              .setLength(blockSplit.getLength()) // max row group size possible
              .setFileLength(fileLength)
              .setLastModificationTime(fileLastModificationTime)
              .setOriginalPath(blockSplit.getPath())
              .setIsDryRun(true)
              .build());
    }
    // Add normal run splits
    if (isCopyIntoSkip) {
      rowGroupSplitAttrs.addAll(
          super.createRowGroupSplitList(
              rowGroupNums, splitPath, blockSplit, fileLength, fileLastModificationTime));
    }
    return rowGroupSplitAttrs;
  }

  /**
   * Adds the file path to known set of files with errors
   *
   * @param path of the file where the error was seen
   */
  public void markFailedFile(Path path) {
    failedFilePaths.add(path);
  }

  /** Describes error that happened during initialization e.g. footer reading, schema comparison */
  private static class SplitExpansionErrorInfo {
    String filePath;
    String errorMessage;

    public SplitExpansionErrorInfo(String filePath, String errorMessage) {
      this.filePath = filePath;
      this.errorMessage = errorMessage;
    }
  }
}
