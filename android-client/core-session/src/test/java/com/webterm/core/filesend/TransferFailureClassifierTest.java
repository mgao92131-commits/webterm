package com.webterm.core.filesend;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;

public class TransferFailureClassifierTest {

    @Test
    public void classifiesHttpStatusMessages() {
        FileTransferException ex = TransferFailureClassifier.classify(
            new IOException("http_503"), TransferPhase.OPENING_HTTP);
        assertEquals(TransferErrorCode.HTTP_AGENT_UNAVAILABLE, ex.code);
        assertEquals(Integer.valueOf(503), ex.httpStatus);
        assertTrue(ex.retryable);
    }

    @Test
    public void classifiesUnexpectedEof() {
        FileTransferException ex = TransferFailureClassifier.classify(
            new IOException("unexpected end of stream"), TransferPhase.RECEIVING);
        assertEquals(TransferErrorCode.NETWORK_UNEXPECTED_EOF, ex.code);
        assertTrue(ex.retryable);
    }

    @Test
    public void classifiesConnectionReset() {
        FileTransferException ex = TransferFailureClassifier.classify(
            new SocketException("Connection reset"), TransferPhase.RECEIVING);
        assertEquals(TransferErrorCode.NETWORK_CONNECTION_RESET, ex.code);
        assertTrue(ex.retryable);
    }

    @Test
    public void classifiesTimeout() {
        FileTransferException ex = TransferFailureClassifier.classify(
            new SocketTimeoutException("timeout"), TransferPhase.OPENING_HTTP);
        assertEquals(TransferErrorCode.NETWORK_TIMEOUT, ex.code);
        assertTrue(ex.retryable);
    }

    @Test
    public void classifiesDiskFullAsStagingOrTarget() {
        FileTransferException staging = TransferFailureClassifier.classify(
            new IOException("No space left on device"), TransferPhase.RECEIVING);
        assertEquals(TransferErrorCode.STAGING_DISK_FULL, staging.code);
        assertFalse(staging.retryable);

        FileTransferException target = TransferFailureClassifier.classify(
            new IOException("No space left on device"), TransferPhase.PUBLISHING);
        assertEquals(TransferErrorCode.TARGET_DISK_FULL, target.code);
    }

    @Test
    public void preservesStructuredException() {
        FileTransferException original = FileTransferException.of(
            TransferErrorCode.TARGET_FINALIZE_FAILED,
            "finalize failed",
            TransferPhase.FINALIZING_TARGET,
            false);
        FileTransferException classified = TransferFailureClassifier.classify(
            original, TransferPhase.PUBLISHING);
        assertEquals(TransferErrorCode.TARGET_FINALIZE_FAILED, classified.code);
        assertEquals(TransferPhase.FINALIZING_TARGET, classified.phase);
    }

    @Test
    public void classifiesEofException() {
        FileTransferException ex = TransferFailureClassifier.classify(
            new EOFException(), TransferPhase.RECEIVING);
        assertEquals(TransferErrorCode.NETWORK_UNEXPECTED_EOF, ex.code);
    }

    @Test
    public void classifiesLegacyMessageCodes() {
        assertEquals(TransferErrorCode.HASH_MISMATCH,
            TransferFailureClassifier.classify(new IOException("hash_mismatch"), TransferPhase.RECEIVING).code);
        assertEquals(TransferErrorCode.SIZE_MISMATCH,
            TransferFailureClassifier.classify(new IOException("size_mismatch"), TransferPhase.RECEIVING).code);
    }
}
