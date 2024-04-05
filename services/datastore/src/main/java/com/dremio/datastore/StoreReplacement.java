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
package com.dremio.datastore;

import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import com.dremio.datastore.api.StoreCreationFunction;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Use to replace stores */
@Retention(RUNTIME)
@Target(TYPE)
public @interface StoreReplacement {
  /**
   * The resource class to be replaced by this resource
   *
   * @return
   */
  Class<? extends StoreCreationFunction> value();
}
