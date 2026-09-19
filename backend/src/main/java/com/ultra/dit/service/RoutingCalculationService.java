package com.ultra.dit.service;

import com.ultra.dit.dto.RoutingRequest;
import com.ultra.dit.dto.RoutingResult;

public interface RoutingCalculationService {

    RoutingResult calculate(RoutingRequest request);
}


