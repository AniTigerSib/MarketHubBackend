package com.worfwint.markethub.auth.jwt;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.worfwint.markethub.auth.jwt.SigningKeyRepository.StoredKey;

/**
 * Holds the RSA key pair used to sign access tokens. On first start the pair is generated
 * and stored in the database, so restarts and other instances reuse it.
 */
@Component
public class SigningKeys {

	private static final Logger log = LoggerFactory.getLogger(SigningKeys.class);

	private static final int RSA_KEY_SIZE = 2048;

	private final SigningKeyRepository repository;

	private final JwtProperties properties;

	private final RSAKey activeKey;

	public SigningKeys(SigningKeyRepository repository, JwtProperties properties, TransactionTemplate tx) {
		this.repository = repository;
		this.properties = properties;
		this.activeKey = tx.execute(status -> loadOrCreateActiveKey());
	}

	/** The private + public key used to sign new access tokens. */
	public RSAKey activeKey() {
		return activeKey;
	}

	/** The public JWKS document other services verify tokens against. Never contains private keys. */
	public Map<String, Object> publicJwks() {
		// A retired key's tokens live at most one access-token TTL after it stopped signing
		Instant retiredAfter = Instant.now().minus(properties.accessTokenTtl());
		List<JWK> keys = repository.findPublished(retiredAfter)
			.stream()
			.<JWK>map(stored -> toRsaKey(stored, false))
			.toList();
		return new JWKSet(keys).toJSONObject(true);
	}

	private RSAKey loadOrCreateActiveKey() {
		repository.lockKeyCreation();
		return repository.findActive().map(stored -> toRsaKey(stored, true)).orElseGet(this::createKey);
	}

	private RSAKey createKey() {
		try {
			KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
			generator.initialize(RSA_KEY_SIZE);
			KeyPair pair = generator.generateKeyPair();
			String kid = UUID.randomUUID().toString();
			repository.insert(kid, JWSAlgorithm.RS256.getName(), pair.getPublic().getEncoded(),
					pair.getPrivate().getEncoded());
			log.info("Generated new access-token signing key {}", kid);
			return toRsaKey(new StoredKey(kid, pair.getPublic().getEncoded(), pair.getPrivate().getEncoded()), true);
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Could not generate RSA key pair", ex);
		}
	}

	private static RSAKey toRsaKey(StoredKey stored, boolean withPrivateKey) {
		try {
			KeyFactory factory = KeyFactory.getInstance("RSA");
			RSAPublicKey publicKey = (RSAPublicKey) factory.generatePublic(new X509EncodedKeySpec(stored.publicKey()));
			RSAKey.Builder builder = new RSAKey.Builder(publicKey).keyID(stored.kid())
				.keyUse(KeyUse.SIGNATURE)
				.algorithm(JWSAlgorithm.RS256);
			if (withPrivateKey) {
				builder.privateKey((RSAPrivateKey) factory.generatePrivate(new PKCS8EncodedKeySpec(stored.privateKey())));
			}
			return builder.build();
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("Stored signing key " + stored.kid() + " is invalid", ex);
		}
	}

}
