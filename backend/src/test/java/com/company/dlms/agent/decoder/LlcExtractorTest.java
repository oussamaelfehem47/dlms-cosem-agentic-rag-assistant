package com.company.dlms.agent.decoder;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class LlcExtractorTest {

    @Test
    void withE6E6Header_strips3Bytes() {
        byte[] info = new byte[]{(byte) 0xE6, (byte) 0xE6, 0x00, 0x01, 0x02};
        assertArrayEquals(new byte[]{0x01, 0x02}, LlcExtractor.extract(info));
    }

    @Test
    void withE6E7Header_strips3Bytes() {
        byte[] info = new byte[]{(byte) 0xE6, (byte) 0xE7, 0x00, 0x01, 0x02};
        assertArrayEquals(new byte[]{0x01, 0x02}, LlcExtractor.extract(info));
    }

    @Test
    void withoutHeader_returnsAsIs() {
        byte[] info = new byte[]{0x01, 0x02, 0x03};
        assertArrayEquals(info, LlcExtractor.extract(info));
    }

    @Test
    void nullInfo_returnsEmpty() {
        assertArrayEquals(new byte[]{}, LlcExtractor.extract(null));
    }
}

