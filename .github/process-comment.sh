#!/usr/bin/env bash
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" >/dev/null 2>&1 && pwd )"
BODY=$(jq -r .comment.body "$GITHUB_EVENT_PATH")
LINES=$(printf "%s" "$BODY" | wc -l)
if [ "$LINES" == "0" ]; then
   if  [[ "$BODY" == /* ]]; then
      echo "Command $BODY is received"
      COMMAND=$(echo "$BODY" | awk '{print $1}' | sed 's/\///')
      ARGS=$(echo "$BODY" | cut -d ' ' -f2-)
      if [ -f "$SCRIPT_DIR/comment-commands/$COMMAND.sh" ]; then
         RESPONSE=$("$SCRIPT_DIR/comment-commands/$COMMAND.sh" "${ARGS[@]}" 2>&1)
      else
         RESPONSE="No such command. \`$COMMAND\` $("$SCRIPT_DIR/comment-commands/help.sh")"
      fi
      set +x #do not display the GITHUB_TOKEN
      COMMENTS_URL=$(jq -r .issue.comments_url "$GITHUB_EVENT_PATH")
      curl -s \
            --data "$(jq --arg body "$RESPONSE" -n '{body: $body}')" \
            --header "authorization: Bearer $GITHUB_TOKEN" \
            --header 'content-type: application/json' \
            "$COMMENTS_URL"
   fi
fi