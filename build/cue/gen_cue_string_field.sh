#!/usr/bin/env bash
# Copyright 2020 The Measurement System Authors
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

output_content() {
  echo "package ${package}"
  echo
  echo "${identifier}: ###\"\"\""
  cat "${infile}"
  echo '"""###'
}

main() {
  local -r package="$1"
  local -r identifier="$2"
  local -r infile="$3"
  local -r outfile="$4"

  output_content > "$outfile"
}

main "$@"