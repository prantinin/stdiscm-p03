package com.example.media.consumer.gui;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Controller serving the browser-based Gallery page (GET /).
 */
@Controller
public class GuiController {

    @GetMapping("/")
    public String index() {
        return "forward:/index.html";
    }
}
