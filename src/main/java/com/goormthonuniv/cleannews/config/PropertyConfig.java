package com.goormthonuniv.cleannews.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.PropertySource;
import org.springframework.context.annotation.PropertySources;

@Configuration
@PropertySource(
        value = "classpath:properties/env.properties",
        ignoreResourceNotFound = true
)
public class PropertyConfig {

}