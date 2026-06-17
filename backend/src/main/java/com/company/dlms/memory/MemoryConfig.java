package com.company.dlms.memory;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(DlmsMemoryProperties.class)
public class MemoryConfig {}

