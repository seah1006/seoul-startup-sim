package com.seah.startupsim.common;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
@RestController
public class DbHealthController {
 private final JdbcTemplate jdbc;
 public DbHealthController(JdbcTemplate jdbc){this.jdbc=jdbc;}
 @GetMapping("/api/health/db")
 public DbHealth health(){return new DbHealth("UP",jdbc.queryForObject("SELECT postgis_version()",String.class));}
 public record DbHealth(String status,String postgisVersion){}
}