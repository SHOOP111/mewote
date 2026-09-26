package io.lattice;

import io.lattice.skein.CryptoShredder;
import io.lattice.skein.StateLedger;
import io.lattice.warp.SubjectId;
import io.lattice.weft.HlcTimestamp;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.lattice.TestSupport.SUBJECT;
import static org.junit.jupiter.api.Assertions.*;

class LedgerTamperTest {
    @Test void ciphertextEditBreaksTheHashChainButCryptoShreddingDoesNot() throws Exception {
        CryptoShredder shredder = new CryptoShredder();
        StateLedger ledger = new StateLedger(shredder);
        ledger.set(SUBJECT, "policy.role", "member", new HlcTimestamp(100, 0, "ledger-1"));
        assertTrue(ledger.verifyChain());
        assertTrue(ledger.cryptoShred(SUBJECT));
        assertTrue(ledger.verifyChain(), "erasing a key must not alter retained ciphertext hashes");

        StateLedger edited = new StateLedger(new CryptoShredder());
        edited.set(SubjectId.java(UUID.fromString("44444444-4444-4444-8444-444444444444")), "state", "value", new HlcTimestamp(101, 0, "ledger-1"));
        Object sealedPayload = edited.events().getFirst().payload();
        var field = sealedPayload.getClass().getDeclaredField("ciphertext");
        field.setAccessible(true);
        byte[] bytes = (byte[]) field.get(sealedPayload);
        bytes[0] ^= 0x01;
        assertFalse(edited.verifyChain(), "a ciphertext edit must invalidate the event hash");
    }
}
