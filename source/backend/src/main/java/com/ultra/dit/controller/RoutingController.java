package com.ultra.dit.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.ultra.dit.service.RoutingOrchestratorService;

@RestController
@RequestMapping("routing")
public class RoutingController {

  private final RoutingOrchestratorService routingOrchestratorService;

  public RoutingController(RoutingOrchestratorService routingOrchestratorService) {
    this.routingOrchestratorService = routingOrchestratorService;
  }

  @GetMapping("process")
  public String process() {

    return routingOrchestratorService.processRouting();
  }

}
