package io.lattice.skein;

import java.util.Arrays;

public final class SealedPayload {
    private final byte[] nonce;
    private final byte[] ciphertext;
    public SealedPayload(byte[] nonce, byte[] ciphertext) {
        this.nonce = nonce.clone();
        this.ciphertext = ciphertext.clone();
    }
    public byte[] nonce() { return nonce.clone(); }
    public byte[] ciphertext() { return ciphertext.clone(); }
    byte[] nonceInternal() { return nonce; }
    byte[] ciphertextInternal() { return ciphertext; }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof SealedPayload payload
                && Arrays.equals(nonce, payload.nonce) && Arrays.equals(ciphertext, payload.ciphertext);
    }
    @Override public int hashCode() { return 31 * Arrays.hashCode(nonce) + Arrays.hashCode(ciphertext); }
}
