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
package com.dremio.exec.catalog.conf;

import com.google.common.base.Objects;
import io.protostuff.Tag;
import javax.validation.constraints.NotBlank;

/** A generic string property associated with a source. */
public class Property {

  @NotBlank
  @Tag(1)
  public String name;

  @NotBlank
  @Tag(2)
  public String value;

  public Property() {}

  public Property(String name, String value) {
    this.name = name;
    this.value = value;
  }

  @Override
  public String toString() {
    return "Property{" + "name='" + name + '\'' + ", value='" + value + '\'' + '}';
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    Property property = (Property) o;
    return Objects.equal(name, property.name) && Objects.equal(value, property.value);
  }

  @Override
  public int hashCode() {
    return Objects.hashCode(name, value);
  }
}
