package com.ultra.dit.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {
  private String message;
  private int status;
  private long timestamp;

  public ErrorResponse(String message, int status) {
    this.message = message;
    this.status = status;
    this.timestamp = System.currentTimeMillis();
  }
}
