/*
 * Copyright (C) 2017 Dremio Corporation
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
import { MAP, LIST, BOOLEAN, TEXT, FLOAT } from 'constants/DataTypes';

// todo: loc
export const UNMATCHED_CELL_VALUE = '???';
export const EMPTY_NULL_VALUE = 'null';
export const EMPTY_STRING_VALUE = 'empty text';

class DataFormatUtils {
  formatValue(value, columnType, row) {
    if (value === undefined) { // note: some APIs don't express null correctly (instead they drop the field)
      return EMPTY_NULL_VALUE;
    }
    if (value === '') {
      return EMPTY_STRING_VALUE;
    }
    if (value === null) {
      if (row && row.get('isDeleted')) {
        return UNMATCHED_CELL_VALUE;
      }
      return EMPTY_NULL_VALUE;
    }
    switch (columnType) {
    case MAP:
    case LIST:
    case BOOLEAN:
      if (typeof value === 'string') {
        return value;
      }
      return JSON.stringify(value);
    case TEXT:
      return value.replace(/^ /g, '\u00a0');  // force showing initial space
    case FLOAT:
      return String(value); // Edge renders float inconsistently, so explicitly convert to String here
    default:
      return value;
    }
  }
}

export default new DataFormatUtils();
