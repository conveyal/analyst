package com.conveyal.taui;

import com.auth0.jwt.JWTVerifier;
import com.conveyal.gtfs.api.ApiMain;
import com.conveyal.taui.controllers.BundleController;
import com.conveyal.taui.controllers.GraphQLController;
import com.conveyal.taui.controllers.ModificationController;
import com.conveyal.taui.controllers.ScenarioController;
import com.conveyal.taui.models.Bundle;
import com.conveyal.taui.persistence.Persistence;
import org.apache.commons.codec.binary.Base64;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static spark.Spark.before;
import static spark.Spark.halt;
import static spark.Spark.port;
import static spark.Spark.staticFileLocation;

/**
 * Main entry point
 */
public class TransportAnalyst {
    private static final Logger LOG = LoggerFactory.getLogger(TransportAnalyst.class);

    private static JWTVerifier verifier;

    public static void main (String... args) throws Exception {
        LOG.info("Starting TAUI server at {}", LocalDateTime.now());

        byte[] auth0Secret = new Base64(true).decode(AnalystConfig.auth0Secret);
        String auth0ClientId = AnalystConfig.auth0ClientId;
        verifier = new JWTVerifier(auth0Secret, auth0ClientId);

        LOG.info("Connecting to database");
        Persistence.initialize();

        LOG.info("Initializing GTFS cache");
        File cacheDir = new File(AnalystConfig.localCache);
        cacheDir.mkdirs();
        ApiMain.initialize(AnalystConfig.offline ? null : AnalystConfig.bundleBucket, AnalystConfig.localCache);

        LOG.info("Starting server");

        port(AnalystConfig.port);

        // serve up index.html which pulls client code from S3
        staticFileLocation("/public");

        // check if a user is authenticated
        before((req, res) -> {
            if ("/".equals(req.pathInfo())) return; // don't need to be authenticated to view main page

            if (!AnalystConfig.offline) {
                String auth = req.headers("Authorization");

                // authorization required
                if (auth == null || auth.isEmpty()) halt(401);

                // make sure it's properly formed
                String[] authComponents = auth.split(" ");

                if (authComponents.length != 2 || !"bearer".equals(authComponents[0].toLowerCase())) halt(400);

                // validate the JWT
                JWTVerifier verifier = new JWTVerifier(auth0Secret, auth0ClientId);

                Map<String, Object> jwt = null;
                try {
                    jwt = verifier.verify(authComponents[1]);
                } catch (Exception e) {
                    LOG.info("Login failed", e);
                    halt(403);
                }

                if (!jwt.containsKey("analyst")) {
                    halt(403);
                }

                String group = null;
                try {
                    group = (String) ((Map<String, Object>) jwt.get("analyst")).get("group");
                } catch (Exception e) {
                    halt(403);
                }

                if (group == null) halt(403);

                req.attribute("group", group);
            } else {
                // hardwire group name if we're working offline
                req.attribute("group", "OFFLINE");
            }
        });

        ModificationController.register();
        ScenarioController.register();
        GraphQLController.register();
        BundleController.register();

        LOG.info("Transport Analyst is ready");
    }
}
