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
package com.dremio.exec.service.jobresults;

import com.dremio.sabot.exec.rpc.ExecToCoordTunnel;
import com.dremio.service.jobresults.client.JobResultsClient;
import com.dremio.services.jobresults.common.JobResultsTunnel;

/** JobResultsClient implementation for Software. */
public class JobResultsSoftwareClient implements JobResultsClient {
  private final ExecToCoordTunnel tunnel;

  public JobResultsSoftwareClient(ExecToCoordTunnel tunnel) {
    this.tunnel = tunnel;
  }

  @Override
  public JobResultsTunnel getTunnel() {
    return tunnel;
  }
}
