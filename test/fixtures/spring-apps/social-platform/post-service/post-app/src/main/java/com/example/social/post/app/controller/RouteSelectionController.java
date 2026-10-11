package com.example.social.post.app.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Read-only overload boundaries for source/route selection acceptance. */
@RestController
@RequestMapping("/route-selection")
public class RouteSelectionController {
  @GetMapping("/plain")
  public String select() {
    return "plain-route";
  }

  @GetMapping("/value/{value}")
  public String select(@PathVariable String value) {
    return "value-route:" + value;
  }
}
