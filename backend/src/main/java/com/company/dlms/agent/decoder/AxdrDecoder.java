package com.company.dlms.agent.decoder;

import com.company.dlms.domain.decoder.*;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class AxdrDecoder {

    private AxdrDecoder() {}

    public static AxdrValue decode(byte[] data) {
        if (data == null || data.length == 0) {
            return new AxdrNull();
        }
        ByteBuffer buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
        return decode(buf);
    }

    static AxdrValue decode(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            return new AxdrNull();
        }

        int tag = u8(buf);
        return switch (tag) {
            case AxdrNull.TAG -> new AxdrNull();
            case AxdrArray.TAG -> new AxdrArray(readElements(buf));
            case AxdrStructure.TAG -> new AxdrStructure(readElements(buf));
            case AxdrBoolean.TAG -> new AxdrBoolean(u8(buf) != 0);
            case AxdrBitString.TAG -> decodeBitString(buf);
            case AxdrInt32.TAG -> new AxdrInt32(i32(buf));
            case AxdrUint32.TAG -> new AxdrUint32(Integer.toUnsignedLong(i32(buf)));
            case AxdrOctetString.TAG -> new AxdrOctetString(readBytesWithLength(buf));
            case AxdrVisibleString.TAG -> new AxdrVisibleString(new String(readBytesWithLength(buf), StandardCharsets.US_ASCII));
            case AxdrUtf8String.TAG -> new AxdrUtf8String(new String(readBytesWithLength(buf), StandardCharsets.UTF_8));
            case AxdrInt8.TAG -> new AxdrInt8(i8(buf));
            case AxdrInt16.TAG -> new AxdrInt16(i16(buf));
            case AxdrUint8.TAG -> new AxdrUint8((short) u8(buf));
            case AxdrUint16.TAG -> new AxdrUint16(u16(buf));
            case AxdrCompactArray.TAG -> new AxdrCompactArray(readBytesWithLength(buf));
            case AxdrInt64.TAG -> new AxdrInt64(i64(buf));
            case AxdrUint64.TAG -> new AxdrUint64(new BigInteger(1, readFixed(buf, 8)));
            case AxdrEnum.TAG -> new AxdrEnum(u8(buf));
            case AxdrFloat32.TAG -> new AxdrFloat32(f32(buf));
            case AxdrFloat64.TAG -> new AxdrFloat64(f64(buf));
            case AxdrDateTime.TAG -> decodeDateTime(buf);
            case AxdrDate.TAG -> decodeDate(buf);
            case AxdrTime.TAG -> decodeTime(buf);
            default -> {
                // Unknown tag fallback: octet string of remaining bytes (never throw)
                byte[] remaining = new byte[buf.remaining()];
                buf.get(remaining);
                yield new AxdrOctetString(remaining);
            }
        };
    }

    private static List<AxdrValue> readElements(ByteBuffer buf) {
        int count = readLengthCount(buf);
        List<AxdrValue> out = new ArrayList<>(Math.max(0, count));
        for (int i = 0; i < count; i++) {
            out.add(decode(buf));
        }
        return out;
    }

    private static AxdrBitString decodeBitString(ByteBuffer buf) {
        int bitLen = readLengthCount(buf);
        int byteLen = (bitLen + 7) / 8;
        byte[] bytes = readFixed(buf, byteLen);
        int unusedBits = (8 - (bitLen % 8)) % 8;
        return new AxdrBitString(bytes, unusedBits);
    }

    private static AxdrDateTime decodeDateTime(ByteBuffer buf) {
        byte[] raw = readFixed(buf, 12);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int year = u16(b);
        byte month = b.get();
        byte dom = b.get();
        byte dow = b.get();
        byte hour = b.get();
        byte min = b.get();
        byte sec = b.get();
        b.get(); // skip 0x?? hundredths as byte
        byte hundredths = raw[8];
        short deviation = b.getShort();
        byte clockStatus = b.get();
        return new AxdrDateTime(year, month, dom, dow, hour, min, sec, hundredths, deviation, clockStatus);
    }

    private static AxdrDate decodeDate(ByteBuffer buf) {
        byte[] raw = readFixed(buf, 5);
        ByteBuffer b = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN);
        int year = u16(b);
        byte month = b.get();
        byte dom = b.get();
        byte dow = b.get();
        return new AxdrDate(year, month, dom, dow);
    }

    private static AxdrTime decodeTime(ByteBuffer buf) {
        byte[] raw = readFixed(buf, 4);
        byte hour = raw[0];
        byte min = raw[1];
        byte sec = raw[2];
        byte hundredths = raw[3];
        return new AxdrTime(hour, min, sec, hundredths);
    }

    private static byte[] readBytesWithLength(ByteBuffer buf) {
        int len = readLengthCount(buf);
        return readFixed(buf, len);
    }

    private static int readLengthCount(ByteBuffer buf) {
        int first = u8(buf);
        if ((first & 0x80) == 0) {
            return first;
        }
        int n = first & 0x7F;
        if (n == 0) {
            return 0;
        }
        if (buf.remaining() < n) {
            throw new AxdrDecodeException("Truncated length field (need " + n + " bytes)");
        }
        int val = 0;
        for (int i = 0; i < n; i++) {
            val = (val << 8) | (buf.get() & 0xFF);
        }
        return val;
    }

    private static byte[] readFixed(ByteBuffer buf, int len) {
        if (len < 0) throw new AxdrDecodeException("Negative length " + len);
        if (buf.remaining() < len) {
            throw new AxdrDecodeException("Truncated AXDR value (need " + len + " bytes, have " + buf.remaining() + ")");
        }
        byte[] out = new byte[len];
        buf.get(out);
        return out;
    }

    private static int u8(ByteBuffer buf) {
        if (!buf.hasRemaining()) {
            throw new AxdrDecodeException("Unexpected end of buffer");
        }
        return buf.get() & 0xFF;
    }

    private static int u16(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            throw new AxdrDecodeException("Unexpected end of buffer for uint16");
        }
        return buf.getShort() & 0xFFFF;
    }

    private static byte i8(ByteBuffer buf) {
        if (buf.remaining() < 1) {
            throw new AxdrDecodeException("Unexpected end of buffer for int8");
        }
        return buf.get();
    }

    private static short i16(ByteBuffer buf) {
        if (buf.remaining() < 2) {
            throw new AxdrDecodeException("Unexpected end of buffer for int16");
        }
        return buf.getShort();
    }

    private static int i32(ByteBuffer buf) {
        if (buf.remaining() < 4) {
            throw new AxdrDecodeException("Unexpected end of buffer for int32");
        }
        return buf.getInt();
    }

    private static long i64(ByteBuffer buf) {
        if (buf.remaining() < 8) {
            throw new AxdrDecodeException("Unexpected end of buffer for int64");
        }
        return buf.getLong();
    }

    private static float f32(ByteBuffer buf) {
        if (buf.remaining() < 4) {
            throw new AxdrDecodeException("Unexpected end of buffer for float32");
        }
        return buf.getFloat();
    }

    private static double f64(ByteBuffer buf) {
        if (buf.remaining() < 8) {
            throw new AxdrDecodeException("Unexpected end of buffer for float64");
        }
        return buf.getDouble();
    }
}

