package com.company.dlms.domain.decoder;

import java.io.Serializable;

public record HdlcFrame(
        FrameType frameType,
        UFrameType uFrameType,
        SFrameType sFrameType,
        int clientSap,
        int serverSap,
        byte[] informationField,
        boolean fcsValid,
        byte[] rawBytes
) implements Serializable {}
