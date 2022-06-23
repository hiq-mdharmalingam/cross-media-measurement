// Copyright 2021 The Cross-Media Measurement Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package k8s

_secret_name: string @tag("secret_name")

#DefaultResourceConfig: {
	replicas: 1
	resources: {
		requests: {
			cpu: "100m"
		}
		limits: {
			cpu:    "400m"
			memory: "512Mi"
		}
	}
	jvmHeapSize: "400m"
}

// Name of K8s service account for the internal API server.
#InternalServerServiceAccount: "internal-server"

objectSets: [
	default_deny_ingress_and_egress,
	kingdom.deployments,
	kingdom.services,
	kingdom.networkPolicies,
]

_imageSuffixes: [_=string]: string
_imageSuffixes: {
	"gcp-kingdom-data-server":   "kingdom/data-server"
	"system-api-server":         "kingdom/system-api"
	"v2alpha-public-api-server": "kingdom/v2alpha-public-api"
	"update-kingdom-schema":     "kingdom/spanner-update-schema"
}
_imageConfigs: [_=string]: #ImageConfig
_imageConfigs: {
	for name, suffix in _imageSuffixes {
		"\(name)": {repoSuffix: suffix}
	}
}

kingdom: #Kingdom & {
	_kingdom_secret_name: _secret_name
	_spannerConfig: database: "kingdom"

	_images: {
		for name, config in _imageConfigs {
			"\(name)": config.image
		}
	}

	_resource_configs: {
		"gcp-kingdom-data-server":   #DefaultResourceConfig
		"system-api-server":         #DefaultResourceConfig
		"v2alpha-public-api-server": #DefaultResourceConfig
	}
	_kingdom_image_pull_policy: "Always"
	_verboseGrpcServerLogging:  true

	deployments: {
		"gcp-kingdom-data-server": {
			_podSpec: #ServiceAccountPodSpec & {
				serviceAccountName: #InternalServerServiceAccount
			}
		}
	}
}
