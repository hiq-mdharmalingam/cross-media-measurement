// Copyright 2020 The Measurement System Authors
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

#include "wfa/measurement/common/crypto/protocol_cryptor.h"

#include "absl/memory/memory.h"
#include "absl/synchronization/mutex.h"
#include "crypto/commutative_elgamal.h"
#include "crypto/context.h"
#include "crypto/ec_commutative_cipher.h"
#include "crypto/ec_group.h"
#include "wfa/measurement/common/crypto/ec_point_util.h"

namespace wfa::measurement::common::crypto {

namespace {
using ::private_join_and_compute::CommutativeElGamal;
using ::private_join_and_compute::Context;
using ::private_join_and_compute::ECCommutativeCipher;
using ::private_join_and_compute::ECGroup;
using ::private_join_and_compute::ECPoint;

class ProtocolCryptorImpl : public ProtocolCryptor {
 public:
  ProtocolCryptorImpl(
      std::unique_ptr<CommutativeElGamal> local_el_gamal_cipher,
      std::unique_ptr<CommutativeElGamal> client_el_gamal_cipher,
      std::unique_ptr<ECCommutativeCipher> local_pohlig_hellman_cipher,
      std::unique_ptr<Context> ctx, ECGroup ec_group);
  ~ProtocolCryptorImpl() override = default;
  ProtocolCryptorImpl(ProtocolCryptorImpl&& other) = delete;
  ProtocolCryptorImpl& operator=(ProtocolCryptorImpl&& other) = delete;
  ProtocolCryptorImpl(const ProtocolCryptorImpl&) = delete;
  ProtocolCryptorImpl& operator=(const ProtocolCryptorImpl&) = delete;

  StatusOr<ElGamalCiphertext> Blind(
      const ElGamalCiphertext& ciphertext) override;
  StatusOr<std::string> Decrypt(const ElGamalCiphertext& ciphertext) override;
  StatusOr<ElGamalCiphertext> ReRandomize(
      const ElGamalCiphertext& ciphertext) override;

 private:
  // A CommutativeElGamal cipher created using local ElGamal Keys, used for
  // encrypting/decrypting local layer of ElGamal encryption.
  std::unique_ptr<CommutativeElGamal> local_el_gamal_cipher_;
  // A CommutativeElGamal cipher created using the combined public key, used
  // for re-randomizing ciphertext and sameKeyAggregation, etc.
  std::unique_ptr<CommutativeElGamal> composite_el_gamal_cipher_;
  // An ECCommutativeCipher used for blinding a ciphertext.
  std::unique_ptr<ECCommutativeCipher> local_pohlig_hellman_cipher_;

  // Context used for storing temporary values to be reused across openssl
  // function calls for better performance.
  std::unique_ptr<Context> ctx_;
  // The EC Group representing the curve definition.
  const ECGroup ec_group_;

  // Since the underlying private-join-and-computer::CommutativeElGamal is NOT
  // thread safe, we use mutex to enforce thread safety in this class.
  absl::Mutex mutex_;
};

ProtocolCryptorImpl::ProtocolCryptorImpl(
    std::unique_ptr<CommutativeElGamal> local_el_gamal_cipher,
    std::unique_ptr<CommutativeElGamal> client_el_gamal_cipher,
    std::unique_ptr<ECCommutativeCipher> local_pohlig_hellman_cipher,
    std::unique_ptr<Context> ctx, ECGroup ec_group)
    : local_el_gamal_cipher_(std::move(local_el_gamal_cipher)),
      composite_el_gamal_cipher_(std::move(client_el_gamal_cipher)),
      local_pohlig_hellman_cipher_(std::move(local_pohlig_hellman_cipher)),
      ctx_(std::move(ctx)),
      ec_group_(std::move(ec_group)) {}

StatusOr<ElGamalCiphertext> ProtocolCryptorImpl::Blind(
    const ElGamalCiphertext& ciphertext) {
  absl::WriterMutexLock l(&mutex_);
  ASSIGN_OR_RETURN(std::string decrypted_el_gamal,
                   local_el_gamal_cipher_->Decrypt(ciphertext));
  ASSIGN_OR_RETURN(ElGamalCiphertext re_encrypted_p_h,
                   local_pohlig_hellman_cipher_->ReEncryptElGamalCiphertext(
                       std::make_pair(ciphertext.first, decrypted_el_gamal)));
  return {std::move(re_encrypted_p_h)};
}

StatusOr<std::string> ProtocolCryptorImpl::Decrypt(
    const ElGamalCiphertext& ciphertext) {
  absl::WriterMutexLock l(&mutex_);
  return local_el_gamal_cipher_->Decrypt(ciphertext);
}

StatusOr<ElGamalCiphertext> ProtocolCryptorImpl::ReRandomize(
    const ElGamalCiphertext& ciphertext) {
  absl::WriterMutexLock l(&mutex_);
  ASSIGN_OR_RETURN(ElGamalCiphertext zero,
                   composite_el_gamal_cipher_->EncryptIdentityElement());
  ASSIGN_OR_RETURN(ElGamalEcPointPair zero_ec,
                   GetElGamalEcPoints(zero, ec_group_));
  ASSIGN_OR_RETURN(ElGamalEcPointPair ciphertext_ec,
                   GetElGamalEcPoints(ciphertext, ec_group_));
  ASSIGN_OR_RETURN(ElGamalEcPointPair result_ec,
                   AddEcPointPairs(zero_ec, ciphertext_ec));

  ElGamalCiphertext result_ciphertext;
  ASSIGN_OR_RETURN(result_ciphertext.first, result_ec.u.ToBytesCompressed());
  ASSIGN_OR_RETURN(result_ciphertext.second, result_ec.e.ToBytesCompressed());
  return {std::move(result_ciphertext)};
}

}  // namespace

StatusOr<std::unique_ptr<ProtocolCryptor>> CreateProtocolCryptorWithKeys(
    int curve_id, const ElGamalCiphertext& local_el_gamal_public_key,
    absl::string_view local_el_gamal_private_key,
    absl::string_view local_pohlig_hellman_private_key,
    const ElGamalCiphertext& composite_el_gamal_public_key) {
  auto ctx = absl::make_unique<Context>();
  ASSIGN_OR_RETURN(ECGroup ec_group, ECGroup::Create(curve_id, ctx.get()));
  ASSIGN_OR_RETURN(auto local_el_gamal_cipher,
                   local_el_gamal_private_key.empty()
                       ? CommutativeElGamal::CreateFromPublicKey(
                             curve_id, local_el_gamal_public_key)
                       : CommutativeElGamal::CreateFromPublicAndPrivateKeys(
                             curve_id, local_el_gamal_public_key,
                             std::string(local_el_gamal_private_key)));
  ASSIGN_OR_RETURN(auto client_el_gamal_cipher,
                   CommutativeElGamal::CreateFromPublicKey(
                       curve_id, composite_el_gamal_public_key));
  ASSIGN_OR_RETURN(
      auto local_pohlig_hellman_cipher,
      local_pohlig_hellman_private_key.empty()
          ? ECCommutativeCipher::CreateWithNewKey(
                curve_id, ECCommutativeCipher::HashType::SHA256)
          : ECCommutativeCipher::CreateFromKey(
                curve_id, std::string(local_pohlig_hellman_private_key),
                ECCommutativeCipher::HashType::SHA256));

  std::unique_ptr<ProtocolCryptor> result =
      absl::make_unique<ProtocolCryptorImpl>(
          std::move(local_el_gamal_cipher), std::move(client_el_gamal_cipher),
          std::move(local_pohlig_hellman_cipher), std::move(ctx),
          std::move(ec_group));
  return {std::move(result)};
}

}  // namespace wfa::measurement::common::crypto
