package dev.agentboard.web;

import dev.agentboard.connect.ConnectorCatalog;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
public class ConnectorCatalogController {
    private final ConnectorCatalog catalog;
    public ConnectorCatalogController(ConnectorCatalog catalog){this.catalog=catalog;}
    @GetMapping("/api/connectors") public Map<String,Object> list(){return catalog.options();}
}
