package demo.jwt;

import java.util.Date;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

final class JwtTokens {

    private JwtTokens() {
    }

    /** Otherwise valid claims, signed with a key the issuer's JWK set doesn't contain. */
    static String signedWithUnknownKey() throws JOSEException {
        RSAKey attackerKey = new RSAKeyGenerator(2048).keyID("attacker-key").generate();
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(attackerKey.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer("https://issuer.demo")
                        .subject("mallory")
                        .issueTime(new Date())
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        jwt.sign(new RSASSASigner(attackerKey));
        return jwt.serialize();
    }

    /** Flip the last characters of the signature. */
    static String tampered(String token) {
        return token.substring(0, token.length() - 4) + (token.endsWith("AAAA") ? "BBBB" : "AAAA");
    }
}
