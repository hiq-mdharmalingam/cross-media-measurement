# Copyright 2020 The Cross-Media Measurement Authors
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

# referred step 3 from https://github.com/world-federation-of-advertisers/cross-media-measurement/blob/main/docs/gke/kingdom-deployment.md

resource "google_kms_key_ring" "default" {
  name = var.ring_name
  location = var.ring_location
}


data "google_kms_key_ring" "my_key_ring" {
  name = var.ring_name
  location = var.ring_location
}


resource "google_kms_crypto_key" "default" {
  name = "gce_east1_symm_key"
  key_ring = data.google_kms_key_ring.my_key_ring.id
}
data "google_iam_policy" "default" {
  binding {
    members = [
      "serviceAccount:service-1049178966878@compute-system.iam.gserviceaccount.com"
    ]
    role = "roles/cloudkms.cryptoKeyEncrypterDecrypter"
  }
}
