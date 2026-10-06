# shellcheck shell=bash
# Pure helpers for S3 multipart XML and ETags — unit-tested offline.

# xml_value <tag> — first <tag>value</tag> on stdin, or empty.
xml_value() { sed -n "s:.*<$1>\([^<]*\)</$1>.*:\1:p" | head -n1; }

# xml_uploads — "<key> <uploadId>" per <Upload> in a ListMultipartUploadsResult on stdin.
xml_uploads() {
  awk 'BEGIN { RS = "<Upload>" } NR > 1 {
    k = $0; sub(/.*<Key>/, "", k); sub(/<\/Key>.*/, "", k)
    u = $0; sub(/.*<UploadId>/, "", u); sub(/<\/UploadId>.*/, "", u)
    print k " " u
  }'
}

# mp_etag <part md5 hex...> — AWS multipart ETag: md5 of the concatenated
# binary digests, then "-<part count>". Pure bash: printf emits the bytes.
mp_etag() {
  local all="" esc="" h i
  for h in "$@"; do all+=$h; done
  for ((i = 0; i < ${#all}; i += 2)); do esc+="\\x${all:i:2}"; done
  # shellcheck disable=SC2059 # the \xHH escapes are the point
  printf "$esc" | md5sum | cut -d' ' -f1 | sed "s/\$/-$#/"
}

# complete_body <partNumber:etag...> — CompleteMultipartUpload request body.
complete_body() {
  local p out="<CompleteMultipartUpload>"
  for p in "$@"; do
    out+="<Part><PartNumber>${p%%:*}</PartNumber><ETag>\"${p#*:}\"</ETag></Part>"
  done
  echo "$out</CompleteMultipartUpload>"
}
