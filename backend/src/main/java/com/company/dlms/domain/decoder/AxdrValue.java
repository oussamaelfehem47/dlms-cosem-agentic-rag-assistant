package com.company.dlms.domain.decoder;

public sealed interface AxdrValue extends java.io.Serializable permits
        AxdrNull, AxdrBoolean,
        AxdrInt8, AxdrInt16, AxdrInt32, AxdrInt64,
        AxdrUint8, AxdrUint16, AxdrUint32, AxdrUint64,
        AxdrFloat32, AxdrFloat64,
        AxdrOctetString, AxdrVisibleString, AxdrUtf8String, AxdrBitString,
        AxdrDateTime, AxdrDate, AxdrTime,
        AxdrEnum, AxdrArray, AxdrStructure, AxdrCompactArray {
    int tag();
}

