PS C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03> mvn -pl consumer -am test
[INFO] Scanning for projects...
[INFO] Loaded 24575 auto-discovered prefixes for remote repository central (prefixes-central.txt)
[INFO] ------------------------------------------------------------------------
[INFO] Detecting the operating system and CPU architecture
[INFO] ------------------------------------------------------------------------
[INFO] os.detected.name: windows
[INFO] os.detected.arch: x86_64
[INFO] os.detected.bitness: 64
[INFO] os.detected.version: 10.0
[INFO] os.detected.version.major: 10
[INFO] os.detected.version.minor: 0
[INFO] os.detected.classifier: windows-x86_64
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Build Order:
[INFO] 
[INFO] media-upload-parent                                                [pom]
[INFO] common                                                             [jar]
[INFO] consumer                                                           [jar]
[INFO] 
[INFO] ---------------< com.example.media:media-upload-parent >----------------
[INFO] Building media-upload-parent 1.0.0-SNAPSHOT                        [1/3]
[INFO]   from pom.xml
[INFO] --------------------------------[ pom ]---------------------------------
[INFO] 
[INFO] ----------------------< com.example.media:common >----------------------
[INFO] Building common 1.0.0-SNAPSHOT                                     [2/3]
[INFO]   from common\pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] Loaded 24571 auto-discovered prefixes for remote repository google-maven-central-copy (prefixes-google-maven-central-copy.txt)
[INFO] Loaded 24575 auto-discovered prefixes for remote repository maven-central (prefixes-maven-central.txt)
[INFO] 
[INFO] --- protobuf:0.6.1:compile (default) @ common ---
[INFO] Compiling 1 proto file(s) to C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\common\target\generated-sources\protobuf\java
[INFO] 
[INFO] --- protobuf:0.6.1:compile-custom (default) @ common ---
[INFO] Compiling 1 proto file(s) to C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\common\target\generated-sources\protobuf\grpc-java
[INFO] 
[INFO] --- resources:3.5.0:resources (default-resources) @ common ---
[INFO] skip non existing resourceDirectory C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\common\src\main\resources
[INFO] Copying 1 resource from src\main\proto to target\classes
[INFO] Copying 1 resource from src\main\proto to target\classes
[INFO] 
[INFO] --- compiler:3.16.0:compile (default-compile) @ common ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 13 source files with javac [debug target 1.8] to target\classes
[WARNING] bootstrap class path is not set in conjunction with -source 8
  not setting the bootstrap class path may lead to class files that cannot run on JDK 8
    --release 8 is recommended instead of -source 8 -target 1.8 because it sets the bootstrap class path automatically
[WARNING] source value 8 is obsolete and will be removed in a future release
[WARNING] target value 8 is obsolete and will be removed in a future release
[WARNING] To suppress warnings about obsolete options, use -Xlint:-options.
[INFO] 
[INFO] --- resources:3.5.0:testResources (default-testResources) @ common ---
[INFO] skip non existing resourceDirectory C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\common\src\test\resources
[INFO] 
[INFO] --- compiler:3.16.0:testCompile (default-testCompile) @ common ---
[INFO] No sources to compile
[INFO] 
[INFO] --- surefire:3.6.1:test (default-test) @ common ---
[INFO] No tests to run.
[INFO] 
[INFO] ---------------------< com.example.media:consumer >---------------------
[INFO] Building consumer 1.0.0-SNAPSHOT                                   [3/3]
[INFO]   from consumer\pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- protobuf:0.6.1:compile (default) @ consumer ---
[INFO] C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\consumer\..\proto does not exist. Review the configuration or consider disabling the plugin.
[INFO] 
[INFO] --- protobuf:0.6.1:compile-custom (default) @ consumer ---
[INFO] C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\consumer\..\proto does not exist. Review the configuration or consider disabling the plugin.
[INFO] 
[INFO] --- resources:3.3.1:resources (default-resources) @ consumer ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO] Copying 3 resources from src\main\resources to target\classes
[INFO] 
[INFO] --- compiler:3.11.0:compile (default-compile) @ consumer ---
[INFO] Changes detected - recompiling the module! :dependency
[INFO] Compiling 20 source files with javac [debug release 17] to target\classes
[INFO] 
[INFO] --- resources:3.3.1:testResources (default-testResources) @ consumer ---
[INFO] Copying 1 resource from src\test\resources to target\test-classes
[INFO] 
[INFO] --- compiler:3.11.0:testCompile (default-testCompile) @ consumer ---
[INFO] Changes detected - recompiling the module! :dependency
[INFO] Compiling 7 source files with javac [debug release 17] to target\test-classes
[INFO] 
[INFO] --- surefire:3.1.2:test (default-test) @ consumer ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running com.example.media.consumer.ConsumerIntegrationTest
02:17:18.163 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer gRPC server listening on port 57766 with 2 workers
02:17:19.641 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer runtime stopped
02:17:19.695 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer gRPC server listening on port 55873 with 2 workers
02:17:19.781 [pool-6-thread-1] INFO com.example.media.consumer.catalog.DefaultVideoCatalog -- Catalog added video id=P1_1795371439749-f42d469d-b801-474b-9196-c37314642bde_same-name.mp4 name=same-name.mp4 (total=1)
02:17:19.781 [pool-6-thread-1] INFO com.example.media.consumer.worker.ConsumerWorker -- Saved uploaded media P1_1795371439749-f42d469d-b801-474b-9196-c37314642bde_same-name.mp4 (total saved=1)
02:17:19.782 [pool-6-thread-2] INFO com.example.media.consumer.catalog.DefaultVideoCatalog -- Catalog added video id=P1_1795371439750-b4e443a1-2242-4fb7-b0c3-8f7a392676db_same-name.mp4 name=same-name.mp4 (total=2)
02:17:19.782 [pool-6-thread-2] INFO com.example.media.consumer.worker.ConsumerWorker -- Saved uploaded media P1_1795371439750-b4e443a1-2242-4fb7-b0c3-8f7a392676db_same-name.mp4 (total saved=2)
02:17:19.813 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer runtime stopped
02:17:19.843 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer gRPC server listening on port 55875 with 1 workers
02:17:20.007 [pool-8-thread-1] INFO com.example.media.consumer.catalog.DefaultVideoCatalog -- Catalog added video id=P0_1795371439993-2898c4ad-b869-4471-a338-a7beaae620dc_held-0.mp4 name=held-0.mp4 (total=1)
02:17:20.008 [pool-8-thread-1] INFO com.example.media.consumer.worker.ConsumerWorker -- Saved uploaded media P0_1795371439993-2898c4ad-b869-4471-a338-a7beaae620dc_held-0.mp4 (total saved=1)
02:17:20.014 [pool-8-thread-1] INFO com.example.media.consumer.catalog.DefaultVideoCatalog -- Catalog added video id=P1_1795371440010-2f52eef5-eab3-4722-aa6d-37f605790cc1_held-1.mp4 name=held-1.mp4 (total=2)
02:17:20.015 [pool-8-thread-1] INFO com.example.media.consumer.worker.ConsumerWorker -- Saved uploaded media P1_1795371440010-2f52eef5-eab3-4722-aa6d-37f605790cc1_held-1.mp4 (total saved=2)
02:17:20.029 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer runtime stopped
02:17:20.050 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer gRPC server listening on port 55877 with 1 workers
02:17:20.114 [pool-10-thread-1] INFO com.example.media.consumer.catalog.DefaultVideoCatalog -- Catalog added video id=P1_1795371440099-b93a3423-8769-4d5d-930c-26605986ec09_first.mp4 name=first.mp4 (total=1)
02:17:20.115 [pool-10-thread-1] INFO com.example.media.consumer.worker.ConsumerWorker -- Saved uploaded media P1_1795371440099-b93a3423-8769-4d5d-930c-26605986ec09_first.mp4 (total saved=1)
02:17:20.129 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer runtime stopped
02:17:20.148 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer gRPC server listening on port 55879 with 1 workers
02:17:20.204 [pool-12-thread-1] INFO com.example.media.consumer.worker.ConsumerWorker -- Compressed P1_1795371440177-918c2c2e-ece5-445c-90d2-b7cba0e9e8d6_compressed.mp4 from 23 bytes to 23 bytes
02:17:20.209 [pool-12-thread-1] INFO com.example.media.consumer.catalog.DefaultVideoCatalog -- Catalog added video id=P1_1795371440177-918c2c2e-ece5-445c-90d2-b7cba0e9e8d6_compressed.mp4 name=compressed.mp4 (total=1)
02:17:20.209 [pool-12-thread-1] INFO com.example.media.consumer.worker.ConsumerWorker -- Saved uploaded media P1_1795371440177-918c2c2e-ece5-445c-90d2-b7cba0e9e8d6_compressed.mp4 (total saved=1)
02:17:20.229 [main] INFO com.example.media.consumer.server.ConsumerRuntime -- Consumer runtime stopped
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 3.843 s -- in com.example.media.consumer.ConsumerIntegrationTest
[INFO] Running com.example.media.consumer.ConsumerVerificationTest

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.5)

2026-11-23 02:17:22.189 [main] INFO  o.a.m.surefire.booter.ForkedBooter - Starting ForkedBooter v3.1.2 using Java 22.0.2 with PID 16060 (started by Francine in C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\consumer)
2026-11-23 02:17:22.193 [main] INFO  o.a.m.surefire.booter.ForkedBooter - No active profile set, falling back to 1 default profile: "default"
2026-11-23 02:17:25.332 [main] INFO  o.s.b.w.e.tomcat.TomcatWebServer - Tomcat initialized with port 0 (http)
2026-11-23 02:17:25.555 [main] INFO  o.a.catalina.core.StandardService - Starting service [Tomcat]
2026-11-23 02:17:25.556 [main] INFO  o.a.catalina.core.StandardEngine - Starting Servlet engine: [Apache Tomcat/10.1.20]
2026-11-23 02:17:25.784 [main] INFO  o.a.c.c.C.[Tomcat].[localhost].[/] - Initializing Spring embedded WebApplicationContext
2026-11-23 02:17:25.784 [main] INFO  o.s.b.w.s.c.ServletWebServerApplicationContext - Root WebApplicationContext: initialization completed in 3444 ms
2026-11-23 02:17:25.992 [main] INFO  c.e.m.c.gui.ConsumerGuiApplication - >>> Initializing DefaultVideoCatalog (USE_FAKE=false) using uploadsDir=C:\Users\Francine\AppData\Local\Temp\junit17457655086038255808\app-uploads
2026-11-23 02:17:26.068 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer gRPC server listening on port 55882 with 2 workers
2026-11-23 02:17:26.382 [main] INFO  o.s.b.a.w.s.WelcomePageHandlerMapping - Adding welcome page: class path resource [static/index.html]
2026-11-23 02:17:27.293 [main] INFO  o.s.b.w.e.tomcat.TomcatWebServer - Tomcat started on port 49236 (http) with context path ''
2026-11-23 02:17:27.314 [main] INFO  o.a.m.surefire.booter.ForkedBooter - Started ForkedBooter in 6.622 seconds (process running for 12.742)
2026-11-23 02:17:28.040 [http-nio-auto-1-exec-1] INFO  o.a.c.c.C.[Tomcat].[localhost].[/] - Initializing Spring DispatcherServlet 'dispatcherServlet'
2026-11-23 02:17:28.040 [http-nio-auto-1-exec-1] INFO  o.s.web.servlet.DispatcherServlet - Initializing Servlet 'dispatcherServlet'
2026-11-23 02:17:28.043 [http-nio-auto-1-exec-1] INFO  o.s.web.servlet.DispatcherServlet - Completed initialization in 1 ms
2026-11-23 02:17:28.282 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer runtime stopped
2026-11-23 02:17:28.358 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer gRPC server listening on port 49240 with 2 workers
2026-11-23 02:17:28.392 [pool-19-thread-2] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=P1_1795371448384-cea27dcf-45f7-4e40-91b1-cf6a08a3d915_same_name.mp4 name=same_name.mp4 (total=1)
2026-11-23 02:17:28.393 [pool-19-thread-2] INFO  c.e.m.consumer.worker.ConsumerWorker - Saved uploaded media P1_1795371448384-cea27dcf-45f7-4e40-91b1-cf6a08a3d915_same_name.mp4 (total saved=1)
2026-11-23 02:17:28.398 [pool-19-thread-1] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=P2_1795371448386-2b43ed5d-74f7-4ec1-95d1-71284323a2d0_same_name.mp4 name=same_name.mp4 (total=2)
2026-11-23 02:17:28.398 [pool-19-thread-1] INFO  c.e.m.consumer.worker.ConsumerWorker - Saved uploaded media P2_1795371448386-2b43ed5d-74f7-4ec1-95d1-71284323a2d0_same_name.mp4 (total saved=2)
2026-11-23 02:17:28.416 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer runtime stopped
2026-11-23 02:17:28.443 [pool-21-thread-1] ERROR c.e.m.consumer.worker.ConsumerWorker - REJECTED uploaded media corrupt.mp4
com.example.media.consumer.media.MediaException: corrupt media
        at com.example.media.consumer.ConsumerVerificationTest$2.probeDuration(ConsumerVerificationTest.java:431)
        at com.example.media.consumer.worker.ConsumerWorker.process(ConsumerWorker.java:95)
        at com.example.media.consumer.worker.ConsumerWorker.processSafely(ConsumerWorker.java:74)
        at com.example.media.consumer.worker.ConsumerWorker.run(ConsumerWorker.java:61)
        at java.base/java.util.concurrent.Executors$RunnableAdapter.call(Executors.java:572)
        at java.base/java.util.concurrent.FutureTask.run(FutureTask.java:317)
        at java.base/java.util.concurrent.ThreadPoolExecutor.runWorker(ThreadPoolExecutor.java:1144)
        at java.base/java.util.concurrent.ThreadPoolExecutor$Worker.run(ThreadPoolExecutor.java:642)
        at java.base/java.lang.Thread.run(Thread.java:1570)
2026-11-23 02:17:28.468 [pool-21-thread-1] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=P1_1795371448461-49fd453c-2011-415e-8f06-1bc2a57187ed_good.mp4 name=good.mp4 (total=1)
2026-11-23 02:17:28.468 [pool-21-thread-1] INFO  c.e.m.consumer.worker.ConsumerWorker - Saved uploaded media P1_1795371448461-49fd453c-2011-415e-8f06-1bc2a57187ed_good.mp4 (total saved=1)
2026-11-23 02:17:28.533 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer gRPC server listening on port 49244 with 1 workers
2026-11-23 02:17:29.162 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer runtime stopped
2026-11-23 02:17:29.229 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer gRPC server listening on port 49250 with 1 workers
2026-11-23 02:17:29.247 [pool-24-thread-1] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=P1_1795371449242-aafa864b-6c1e-4ca1-965b-834074ede2ae_first.mp4 name=first.mp4 (total=1)
2026-11-23 02:17:29.248 [pool-24-thread-1] INFO  c.e.m.consumer.worker.ConsumerWorker - Saved uploaded media P1_1795371449242-aafa864b-6c1e-4ca1-965b-834074ede2ae_first.mp4 (total saved=1)
2026-11-23 02:17:29.277 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer runtime stopped
2026-11-23 02:17:29.320 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer gRPC server listening on port 49253 with 1 workers
2026-11-23 02:17:30.873 [pool-26-thread-1] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=P0_1795371450862-d822d117-a90d-4007-8095-13e5bdb97a65_producer-0.mp4 name=producer-0.mp4 (total=1)
2026-11-23 02:17:30.873 [pool-26-thread-1] INFO  c.e.m.consumer.worker.ConsumerWorker - Saved uploaded media P0_1795371450862-d822d117-a90d-4007-8095-13e5bdb97a65_producer-0.mp4 (total saved=1)
2026-11-23 02:17:32.386 [pool-26-thread-1] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=P2_1795371452384-c754dc72-d420-4ff1-a1d4-27d0ac5424f2_producer-2.mp4 name=producer-2.mp4 (total=2)
2026-11-23 02:17:32.386 [pool-26-thread-1] INFO  c.e.m.consumer.worker.ConsumerWorker - Saved uploaded media P2_1795371452384-c754dc72-d420-4ff1-a1d4-27d0ac5424f2_producer-2.mp4 (total saved=2)
2026-11-23 02:17:32.401 [main] INFO  c.e.m.c.server.ConsumerRuntime - Consumer runtime stopped
[INFO] Tests run: 14, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 12.15 s -- in com.example.media.consumer.ConsumerVerificationTest
[INFO] Running com.example.media.consumer.dedupe.DedupeRegistryTest
[INFO] Tests run: 11, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.024 s -- in com.example.media.consumer.dedupe.DedupeRegistryTest
[INFO] Running com.example.media.consumer.gui.GuiEndpointsTest

  .   ____          _            __ _ _
 /\\ / ___'_ __ _ _(_)_ __  __ _ \ \ \ \
( ( )\___ | '_ | '_| | '_ \/ _` | \ \ \ \
 \\/  ___)| |_)| | | | | || (_| |  ) ) ) )
  '  |____| .__|_| |_|_| |_\__, | / / / /
 =========|_|==============|___/=/_/_/_/
 :: Spring Boot ::                (v3.2.5)

2026-11-23 02:17:32.676 [main] INFO  c.e.m.consumer.gui.GuiEndpointsTest - Starting GuiEndpointsTest using Java 22.0.2 with PID 16060 (started by Francine in C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\consumer)
2026-11-23 02:17:32.676 [main] INFO  c.e.m.consumer.gui.GuiEndpointsTest - No active profile set, falling back to 1 default profile: "default"
2026-11-23 02:17:33.028 [main] INFO  c.e.m.c.gui.ConsumerGuiApplication - >>> Initializing FakeVideoCatalog (USE_FAKE=true) for GUI development & testing per ?6.4
2026-11-23 02:17:33.034 [main] INFO  c.e.m.c.catalog.FakeVideoCatalog - FakeVideoCatalog found 4 real local sample video files for mock playback
2026-11-23 02:17:33.035 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-1 name=short5.mp4 (total=1)
2026-11-23 02:17:33.035 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-2 name=sample15.mp4 (total=2)
2026-11-23 02:17:33.036 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-3 name=large120.mp4 (total=3)
2026-11-23 02:17:33.036 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-4 name=lecture_intro.mp4 (total=4)
2026-11-23 02:17:33.036 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-5 name=demo_clip_01.mp4 (total=5)
2026-11-23 02:17:33.036 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-6 name=drone_footage.mp4 (total=6)
2026-11-23 02:17:33.037 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-7 name=nature_walk.mp4 (total=7)
2026-11-23 02:17:33.037 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-8 name=presentation_final.mp4 (total=8)
2026-11-23 02:17:33.037 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-9 name=timelapse_sunset.mp4 (total=9)
2026-11-23 02:17:33.037 [main] INFO  c.e.m.c.catalog.DefaultVideoCatalog - Catalog added video id=mock-10 name=traffic_study.mp4 (total=10)
2026-11-23 02:17:33.037 [main] INFO  c.e.m.c.catalog.FakeVideoCatalog - Initialized FakeVideoCatalog with 10 mock videos.
2026-11-23 02:17:33.101 [main] INFO  o.s.b.a.w.s.WelcomePageHandlerMapping - Adding welcome page: class path resource [static/index.html]
2026-11-23 02:17:33.256 [main] INFO  o.s.b.t.m.w.SpringBootMockServletContext - Initializing Spring TestDispatcherServlet ''
2026-11-23 02:17:33.257 [main] INFO  o.s.t.w.s.TestDispatcherServlet - Initializing Servlet ''
2026-11-23 02:17:33.258 [main] INFO  o.s.t.w.s.TestDispatcherServlet - Completed initialization in 1 ms
2026-11-23 02:17:33.273 [main] INFO  c.e.m.consumer.gui.GuiEndpointsTest - Started GuiEndpointsTest in 0.648 seconds (process running for 18.701)

MockHttpServletRequest:
      HTTP Method = GET
      Request URI = /thumb/mock-1
       Parameters = {}
          Headers = []
             Body = null
    Session Attrs = {}

Handler:
             Type = org.springframework.web.servlet.resource.ResourceHttpRequestHandler

Async:
    Async started = false
     Async result = null

Resolved Exception:
             Type = org.springframework.web.servlet.resource.NoResourceFoundException

ModelAndView:
        View name = null
             View = null
            Model = null

FlashMap:
       Attributes = null

MockHttpServletResponse:
           Status = 404
    Error message = No static resource thumb/mock-1.
          Headers = [Vary:"Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"]
     Content type = null
             Body = 
    Forwarded URL = null
   Redirected URL = null
          Cookies = []

MockHttpServletRequest:
      HTTP Method = GET
      Request URI = /media/mock-1
       Parameters = {}
          Headers = [Range:"bytes=0-100"]
             Body = null
    Session Attrs = {}

Handler:
             Type = org.springframework.web.servlet.resource.ResourceHttpRequestHandler

Async:
    Async started = false
     Async result = null

Resolved Exception:
             Type = org.springframework.web.servlet.resource.NoResourceFoundException

ModelAndView:
        View name = null
             View = null
            Model = null

FlashMap:
       Attributes = null

MockHttpServletResponse:
           Status = 404
    Error message = No static resource media/mock-1.
          Headers = [Vary:"Origin", "Access-Control-Request-Method", "Access-Control-Request-Headers"]
     Content type = null
             Body = 
    Forwarded URL = null
   Redirected URL = null
          Cookies = []
[ERROR] Tests run: 4, Failures: 2, Errors: 0, Skipped: 0, Time elapsed: 2.664 s <<< FAILURE! -- in com.example.media.consumer.gui.GuiEndpointsTest
[ERROR] com.example.media.consumer.gui.GuiEndpointsTest.testGetThumbnailReturnsSvgOrImage -- Time elapsed: 1.637 s <<< FAILURE!
java.lang.AssertionError: Status expected:<200> but was:<404>
        at org.springframework.test.util.AssertionErrors.fail(AssertionErrors.java:59)
        at org.springframework.test.util.AssertionErrors.assertEquals(AssertionErrors.java:122)
        at org.springframework.test.web.servlet.result.StatusResultMatchers.lambda$matcher$9(StatusResultMatchers.java:637)
        at org.springframework.test.web.servlet.MockMvc$1.andExpect(MockMvc.java:214)
        at com.example.media.consumer.gui.GuiEndpointsTest.testGetThumbnailReturnsSvgOrImage(GuiEndpointsTest.java:48)
        at java.base/java.lang.reflect.Method.invoke(Method.java:580)
        at java.base/java.util.ArrayList.forEach(ArrayList.java:1597)
        at java.base/java.util.ArrayList.forEach(ArrayList.java:1597)

[ERROR] com.example.media.consumer.gui.GuiEndpointsTest.testMediaRangeRequestSeekingSupport -- Time elapsed: 0.005 s <<< FAILURE!
java.lang.AssertionError: Status expected:<206> but was:<404>
        at org.springframework.test.util.AssertionErrors.fail(AssertionErrors.java:59)
        at org.springframework.test.util.AssertionErrors.assertEquals(AssertionErrors.java:122)
        at org.springframework.test.web.servlet.result.StatusResultMatchers.lambda$matcher$9(StatusResultMatchers.java:637)
        at org.springframework.test.web.servlet.MockMvc$1.andExpect(MockMvc.java:214)
        at com.example.media.consumer.gui.GuiEndpointsTest.testMediaRangeRequestSeekingSupport(GuiEndpointsTest.java:56)
        at java.base/java.lang.reflect.Method.invoke(Method.java:580)
        at java.base/java.util.ArrayList.forEach(ArrayList.java:1597)
        at java.base/java.util.ArrayList.forEach(ArrayList.java:1597)

[INFO] Running com.example.media.consumer.media.MediaProcessorTest
[INFO] Tests run: 15, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 8.668 s -- in com.example.media.consumer.media.MediaProcessorTest
[INFO] Running com.example.media.consumer.queue.LeakyBucketTest
[INFO] Tests run: 2, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.005 s -- in com.example.media.consumer.queue.LeakyBucketTest
[INFO] Running com.example.media.consumer.server.MediaUploadServiceImplTest
[INFO] Tests run: 6, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.094 s -- in com.example.media.consumer.server.MediaUploadServiceImplTest
[INFO] 
[INFO] Results:
[INFO] 
[ERROR] Failures: 
[ERROR]   GuiEndpointsTest.testGetThumbnailReturnsSvgOrImage:48 Status expected:<200> but was:<404>
[ERROR]   GuiEndpointsTest.testMediaRangeRequestSeekingSupport:56 Status expected:<206> but was:<404>
[INFO] 
[ERROR] Tests run: 57, Failures: 2, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] Reactor Summary for media-upload-parent 1.0.0-SNAPSHOT:
[INFO] 
[INFO] media-upload-parent ................................ SUCCESS [  0.005 s]
[INFO] common ............................................. SUCCESS [ 10.500 s]
[ERROR] consumer ........................................... FAILURE [ 40.379 s]
[INFO] ------------------------------------------------------------------------
[ERROR] BUILD FAILURE
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  53.042 s
[INFO] Finished at: 2026-11-23T02:17:44+08:00
[INFO] ------------------------------------------------------------------------
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-surefire-plugin:3.1.2:test (default-test) on project consumer: There are test failures.
[ERROR] 
[ERROR] Please refer to C:\Users\Francine\OneDrive\Desktop\college\Y4\T1\stdiscm\probsets\p03\stdiscm-p03\consumer\target\surefire-reports for the individual test results.
[ERROR] Please refer to dump files (if any exist) [date].dump, [date]-jvmRun[N].dump and [date].dumpstream.
[ERROR] -> [Help 1]
[ERROR] 
[ERROR] To see the full stack trace of the errors, re-run Maven with the -e switch.
[ERROR] Re-run Maven using the -X switch to enable full debug logging.
[ERROR] 
[ERROR] For more information about the errors and possible solutions, please read the following articles:
[ERROR] [Help 1] http://cwiki.apache.org/confluence/display/MAVEN/MojoFailureException
[ERROR] 
[ERROR] After correcting the problems, you can resume the build with the command
[ERROR]   mvn <args> -rf :consumer