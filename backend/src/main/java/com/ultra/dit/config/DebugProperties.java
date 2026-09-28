package com.ultra.dit.config;

import java.nio.file.Path;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import com.ultra.dit.util.DebugUtils;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Component
@ConfigurationProperties(prefix = "app.debug-utils")
@AllArgsConstructor
@NoArgsConstructor
public class DebugProperties {
  private Path debugFolder = DebugUtils.DEFAULT_DEBUG_FOLDER;

  private Boolean removeDebugFolderBeforeCreate;

  private Boolean disabled;

  // URL по которому будут доступны статические файлы дебага
  private String staticDebugFilesUrl;
}
