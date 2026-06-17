package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.ApduType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApduClassifierTest {

    @Test void getRequest() { assertEquals(ApduType.GET_REQUEST, ApduClassifier.classify(new byte[]{(byte) 0xC0})); }
    @Test void getResponse() { assertEquals(ApduType.GET_RESPONSE, ApduClassifier.classify(new byte[]{(byte) 0xC4})); }
    @Test void setRequest() { assertEquals(ApduType.SET_REQUEST, ApduClassifier.classify(new byte[]{(byte) 0xC1})); }
    @Test void setResponse() { assertEquals(ApduType.SET_RESPONSE, ApduClassifier.classify(new byte[]{(byte) 0xC5})); }
    @Test void actionRequest() { assertEquals(ApduType.ACTION_REQUEST, ApduClassifier.classify(new byte[]{(byte) 0xC3})); }
    @Test void actionResponse() { assertEquals(ApduType.ACTION_RESPONSE, ApduClassifier.classify(new byte[]{(byte) 0xC7})); }
    @Test void aarq() { assertEquals(ApduType.AARQ, ApduClassifier.classify(new byte[]{0x60})); }
    @Test void aare() { assertEquals(ApduType.AARE, ApduClassifier.classify(new byte[]{0x61})); }
    @Test void gbt() { assertEquals(ApduType.GBT, ApduClassifier.classify(new byte[]{(byte) 0xE6})); }
    @Test void dataNotification() { assertEquals(ApduType.DATA_NOTIFICATION, ApduClassifier.classify(new byte[]{0x01})); }

    @Test
    void unknownTag() {
        assertEquals(ApduType.UNKNOWN, ApduClassifier.classify(new byte[]{(byte) 0xFF}));
    }

    @Test
    void emptyInput() {
        assertEquals(ApduType.UNKNOWN, ApduClassifier.classify(new byte[]{}));
    }
}

