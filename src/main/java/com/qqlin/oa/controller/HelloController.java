package com.qqlin.oa.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HelloController {
@GetMapping("/hello")
public String hello() {
    return "OA System is running";
}
}
