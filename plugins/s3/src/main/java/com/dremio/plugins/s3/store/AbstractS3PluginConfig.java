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
package com.dremio.plugins.s3.store;

import static com.dremio.plugins.s3.store.S3StoragePlugin.ACCESS_KEY_PROVIDER;

import com.dremio.common.exceptions.UserException;
import com.dremio.exec.catalog.conf.DefaultCtasFormatSelection;
import com.dremio.exec.catalog.conf.DisplayMetadata;
import com.dremio.exec.catalog.conf.NotMetadataImpacting;
import com.dremio.exec.catalog.conf.Property;
import com.dremio.exec.catalog.conf.Secret;
import com.dremio.exec.catalog.conf.SecretRef;
import com.dremio.exec.store.dfs.CacheProperties;
import com.dremio.exec.store.dfs.FileSystemConf;
import com.dremio.exec.store.dfs.SchemaMutability;
import com.dremio.io.file.Path;
import com.dremio.options.OptionManager;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.protostuff.Tag;
import java.util.List;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import org.apache.hadoop.fs.s3a.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Connection Configuration for S3. */
public abstract class AbstractS3PluginConfig
    extends FileSystemConf<AbstractS3PluginConfig, S3StoragePlugin> {
  @Tag(1)
  @DisplayMetadata(label = "AWS Access Key")
  public String accessKey = "";

  @Tag(2)
  @Secret
  @DisplayMetadata(label = "AWS Access Secret")
  public SecretRef accessSecret = SecretRef.empty();

  @Tag(3)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Encrypt connection")
  public boolean secure = true;

  @Tag(4)
  @DisplayMetadata(label = "Buckets")
  public List<String> externalBucketList;

  @Tag(5)
  @DisplayMetadata(label = "Connection Properties")
  public List<Property> propertyList;

  @Tag(6)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable exports into the source (CTAS and DROP)")
  @JsonIgnore
  public boolean allowCreateDrop;

  @Tag(7)
  @DisplayMetadata(label = "Root Path")
  public String rootPath = "/";

  @Tag(9)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable asynchronous access when possible")
  public boolean enableAsync = true;

  @Tag(10)
  @DisplayMetadata(label = "Enable compatibility mode")
  public boolean compatibilityMode = false;

  @Tag(11)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable local caching when possible")
  public boolean isCachingEnabled = true;

  @Tag(12)
  @NotMetadataImpacting
  @Min(value = 1, message = "Max percent of total available cache space must be between 1 and 100")
  @Max(
      value = 100,
      message = "Max percent of total available cache space must be between 1 and 100")
  @DisplayMetadata(label = "Max percent of total available cache space to use when possible")
  public int maxCacheSpacePct = 100;

  @Tag(13)
  @DisplayMetadata(label = "Allowlisted buckets")
  public List<String> whitelistedBuckets;

  @Tag(15)
  @DisplayMetadata(label = "IAM Role to Assume")
  public String assumedRoleARN;

  @Tag(16)
  @DisplayMetadata(label = "Server side encryption key ARN")
  @NotMetadataImpacting
  public String kmsKeyARN;

  @Tag(17)
  @DisplayMetadata(label = "Apply requester-pays to S3 requests")
  public boolean requesterPays = false;

  @Tag(18)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable file status check")
  public boolean enableFileStatusCheck = true;

  @Tag(20)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Default CTAS Format")
  public DefaultCtasFormatSelection defaultCtasFormat = DefaultCtasFormatSelection.ICEBERG;

  @Tag(21)
  @NotMetadataImpacting
  @DisplayMetadata(label = "Enable partition column inference")
  public boolean isPartitionInferenceEnabled = false;

  @Override
  public Path getPath() {
    return Path.of(rootPath);
  }

  @Override
  public boolean isImpersonationEnabled() {
    return false;
  }

  @Override
  public String getConnection() {
    return CloudFileSystemScheme.S3_FILE_SYSTEM_SCHEME.getScheme() + ":///";
  }

  @Override
  public SchemaMutability getSchemaMutability() {
    return SchemaMutability.USER_TABLE;
  }

  @Override
  public boolean isPartitionInferenceEnabled() {
    return isPartitionInferenceEnabled;
  }

  @Override
  public List<Property> getProperties() {
    return propertyList;
  }

  @Override
  public boolean isAsyncEnabled() {
    return enableAsync;
  }

  @Override
  public String getDefaultCtasFormat() {
    return defaultCtasFormat.getDefaultCtasFormat();
  }

  @Override
  public CacheProperties getCacheProperties() {
    return new CacheProperties() {
      @Override
      public boolean isCachingEnabled(final OptionManager optionManager) {
        return isCachingEnabled;
      }

      @Override
      public int cacheMaxSpaceLimitPct() {
        return maxCacheSpacePct;
      }
    };
  }

  protected static String getAccessKeyProvider(
      List<Property> finalProperties, String accessKey, SecretRef accessSecret) {
    Logger logger = LoggerFactory.getLogger(AbstractS3PluginConfig.class);
    if (("".equals(accessKey)) || (SecretRef.isNullOrEmpty(accessSecret))) {
      throw UserException.validationError()
          .message(
              "Failure creating S3 connection. You must provide AWS Access Key and AWS Access Secret.")
          .build(logger);
    }
    finalProperties.add(new Property(Constants.ACCESS_KEY, accessKey));
    // TODO (DX-87446): Instead of resolving here, pass raw secret and define a custom
    // AWSCredentialsProvider
    finalProperties.add(new Property(Constants.SECRET_KEY, accessSecret.get()));
    return ACCESS_KEY_PROVIDER;
  }
}
