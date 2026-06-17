package com.company.dlms.agent.profile;

import com.company.dlms.domain.decoder.AxdrArray;
import com.company.dlms.domain.decoder.AxdrInt8;
import com.company.dlms.domain.decoder.AxdrOctetString;
import com.company.dlms.domain.decoder.AxdrStructure;
import com.company.dlms.domain.decoder.AxdrUint16;
import com.company.dlms.domain.profile.CaptureObjectDef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CaptureObjectParserTest {

    @Test
    void parse_validCaptureObject_returnsDefinition() {
        AxdrArray array = new AxdrArray(List.of(captureObject("1.0.1.8.0.255")));

        List<CaptureObjectDef> result = CaptureObjectParser.parse(array);

        assertThat(result).containsExactly(new CaptureObjectDef(3, "1.0.1.8.0.255", 2, 0));
    }

    @Test
    void formatObis_formatsSixBytes() {
        String obis = CaptureObjectParser.formatObis(new byte[] { 1, 0, 1, 8, 0, (byte) 255 });

        assertThat(obis).isEqualTo("1.0.1.8.0.255");
    }

    @Test
    void parse_emptyArray_returnsEmptyList() {
        assertThat(CaptureObjectParser.parse(new AxdrArray(List.of()))).isEmpty();
    }

    @Test
    void parse_malformedStructure_skipsEntry() {
        AxdrArray array = new AxdrArray(List.of(new AxdrStructure(List.of(
                new AxdrUint16(3),
                new AxdrOctetString(new byte[] { 1, 0, 1, 8, 0, (byte) 255 }),
                new AxdrInt8((byte) 2)
        ))));

        assertThat(CaptureObjectParser.parse(array)).isEmpty();
    }

    @Test
    void parse_invalidField_skipsGracefully() {
        AxdrArray array = new AxdrArray(List.of(new AxdrStructure(List.of(
                new AxdrUint16(3),
                new AxdrOctetString(new byte[] { 1, 2, 3 }),
                new AxdrInt8((byte) 2),
                new AxdrUint16(0)
        ))));

        assertThat(CaptureObjectParser.parse(array)).isEmpty();
    }

    private AxdrStructure captureObject(String obis) {
        String[] parts = obis.split("\\.");
        byte[] bytes = new byte[6];
        for (int index = 0; index < parts.length; index++) {
            bytes[index] = (byte) Integer.parseInt(parts[index]);
        }
        return new AxdrStructure(List.of(
                new AxdrUint16(3),
                new AxdrOctetString(bytes),
                new AxdrInt8((byte) 2),
                new AxdrUint16(0)
        ));
    }
}
