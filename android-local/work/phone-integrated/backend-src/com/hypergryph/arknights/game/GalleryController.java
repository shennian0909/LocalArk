package com.hypergryph.arknights.game;

import com.alibaba.fastjson.JSONObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/gallery")
public class GalleryController {
    private static final String LOCAL_BASE_URL = "http://127.0.0.1:10029";

    @PostMapping(value = "/getThumbnailUrl", produces = "application/json;charset=UTF-8")
    public JSONObject getThumbnailUrl(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> GallerySupport.getThumbnailUrl(user, body, LOCAL_BASE_URL));
    }

    @PostMapping(value = "/saveDiyMagazineV1", produces = "application/json;charset=UTF-8")
    public JSONObject saveDiyMagazineV1(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request,
                user -> GallerySupport.saveDiyMagazineV1(user, body));
    }

    @PostMapping(value = "/saveDiyMagazineV2", produces = "application/json;charset=UTF-8")
    public JSONObject saveDiyMagazineV2(@RequestBody byte[] payload,
            HttpServletRequest request, HttpServletResponse response) {
        String contentType = request.getHeader("Content-Type");
        return ShopTransactionSupport.execute(response, request,
                user -> GallerySupport.saveDiyMagazineV2(user, payload, contentType));
    }

    @GetMapping("/thumbnail/{fileName}")
    public void thumbnail(@PathVariable("fileName") String fileName,
            HttpServletResponse response) throws IOException {
        Path file = GallerySupport.thumbnailFile(fileName);
        if (!Files.isRegularFile(file)) {
            response.sendError(404);
            return;
        }
        response.setStatus(HttpServletResponse.SC_OK);
        response.setContentType(fileName.endsWith(".png") ? "image/png" : "image/jpeg");
        response.setHeader("Cache-Control", "private, max-age=3600");
        response.setContentLengthLong(Files.size(file));
        Files.copy(file, response.getOutputStream());
    }

    @PostMapping(value = "/getFirstRewards", produces = "application/json;charset=UTF-8")
    public JSONObject getFirstRewards(@RequestBody(required = false) JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, GallerySupport::getFirstRewards);
    }

    @PostMapping(value = "/getCollectionRewards", produces = "application/json;charset=UTF-8")
    public JSONObject getCollectionRewards(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> GallerySupport.getCollectionRewards(user, body));
    }

    @PostMapping(value = "/changeMagazineSquad", produces = "application/json;charset=UTF-8")
    public JSONObject changeMagazineSquad(@RequestBody JSONObject body,
            HttpServletRequest request, HttpServletResponse response) {
        return ShopTransactionSupport.execute(response, request, user -> GallerySupport.changeMagazineSquad(user, body));
    }
}
