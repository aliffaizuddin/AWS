# shellcheck shell=bash
# shellcheck source-path=SCRIPTDIR
# shellcheck source=../lib/s3xml.sh
source "$CHAOS_DIR/lib/s3xml.sh"

test_mp_etag_matches_aws_algorithm() {
  # md5("hello"), md5("world") -> computed independently with Python hashlib
  assert_eq "065947336a2f2a95ba8899f3675c3be6-2" \
    "$(mp_etag 5d41402abc4b2a76b9719d911017c592 7d793037a0760186574b0282f2f435e7)" "mp_etag: two parts"
}

test_xml_value_extracts_first_tag() {
  assert_eq "abc-123" \
    "$(xml_value UploadId <<<'<InitiateMultipartUploadResult><Bucket>b</Bucket><UploadId>abc-123</UploadId></InitiateMultipartUploadResult>')" \
    "xml_value: upload id"
  assert_eq "" "$(xml_value Missing <<<'<a>b</a>')" "xml_value: missing tag is empty"
}

test_xml_uploads_lists_key_and_id_pairs() {
  local out
  out=$(xml_uploads <<<'<ListMultipartUploadsResult><Bucket>b</Bucket><Upload><Key>k1</Key><UploadId>u1</UploadId><Initiated>t</Initiated></Upload><Upload><Key>dir/k2</Key><UploadId>u2</UploadId></Upload></ListMultipartUploadsResult>')
  assert_eq $'k1 u1\ndir/k2 u2' "$out" "xml_uploads: pairs in order"
  assert_eq "" "$(xml_uploads <<<'<ListMultipartUploadsResult><Bucket>b</Bucket></ListMultipartUploadsResult>')" "xml_uploads: none"
}

test_complete_body_builds_parts_in_order() {
  assert_eq '<CompleteMultipartUpload><Part><PartNumber>1</PartNumber><ETag>"aa"</ETag></Part><Part><PartNumber>2</PartNumber><ETag>"bb"</ETag></Part></CompleteMultipartUpload>' \
    "$(complete_body 1:aa 2:bb)" "complete_body: two parts"
}
