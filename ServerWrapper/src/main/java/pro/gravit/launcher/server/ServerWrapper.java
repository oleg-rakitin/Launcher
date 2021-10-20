package pro.gravit.launcher.server;

import pro.gravit.launcher.ClientPermissions;
import pro.gravit.launcher.Launcher;
import pro.gravit.launcher.LauncherConfig;
import pro.gravit.launcher.config.JsonConfigurable;
import pro.gravit.launcher.events.request.AuthRequestEvent;
import pro.gravit.launcher.events.request.ProfilesRequestEvent;
import pro.gravit.launcher.modules.events.PostInitPhase;
import pro.gravit.launcher.modules.events.PreConfigPhase;
import pro.gravit.launcher.profiles.ClientProfile;
import pro.gravit.launcher.profiles.PlayerProfile;
import pro.gravit.launcher.profiles.optional.actions.OptionalAction;
import pro.gravit.launcher.profiles.optional.triggers.OptionalTrigger;
import pro.gravit.launcher.request.Request;
import pro.gravit.launcher.request.auth.AuthRequest;
import pro.gravit.launcher.request.auth.GetAvailabilityAuthRequest;
import pro.gravit.launcher.request.auth.RestoreRequest;
import pro.gravit.launcher.request.update.ProfilesRequest;
import pro.gravit.launcher.server.setup.ServerWrapperSetup;
import pro.gravit.utils.PublicURLClassLoader;
import pro.gravit.utils.helper.IOHelper;
import pro.gravit.utils.helper.LogHelper;

import java.io.IOException;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Type;
import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class ServerWrapper extends JsonConfigurable<ServerWrapper.Config> {
    public static final Path configFile = Paths.get(System.getProperty("serverwrapper.configFile", "ServerWrapperConfig.json"));
    public static final boolean disableSetup = Boolean.parseBoolean(System.getProperty("serverwrapper.disableSetup", "false"));
    public static ServerWrapper wrapper;
    public Config config;
    public PublicURLClassLoader ucp;
    public ClassLoader loader;
    public ClientPermissions permissions;
    public ClientProfile profile;
    public PlayerProfile playerProfile;
    public ClientProfile.ServerProfile serverProfile;

    public ServerWrapper(Type type, Path configPath) {
        super(type, configPath);
    }

    public static void initGson() {
        Launcher.gsonManager = new ServerWrapperGsonManager();
        Launcher.gsonManager.initGson();
    }

    public static void main(String... args) throws Throwable {
        LogHelper.printVersion("ServerWrapper");
        LogHelper.printLicense("ServerWrapper");
        ServerWrapper.wrapper = new ServerWrapper(ServerWrapper.Config.class, configFile);
        ServerWrapper.wrapper.run(args);
    }

    public void restore() throws Exception {
        if(config.oauth != null) {
            Request.setOAuth(config.authId, config.oauth, config.oauthExpireTime);
        }
        if(config.extendedTokens != null) {
            Request.addAllExtendedToken(config.extendedTokens);
        }
        Request.restore();
    }

    public ProfilesRequestEvent getProfiles() throws Exception {
        ProfilesRequestEvent result = new ProfilesRequest().request();
        for (ClientProfile p : result.profiles) {
            LogHelper.debug("Get profile: %s", p.getTitle());
            boolean isFound = false;
            for (ClientProfile.ServerProfile srv : p.getServers()) {
                if (srv != null && srv.name.equals(config.serverName)) {
                    this.serverProfile = srv;
                    this.profile = p;
                    Launcher.profile = p;
                    LogHelper.debug("Found profile: %s", Launcher.profile.getTitle());
                    isFound = true;
                    break;
                }
            }
            if (isFound) break;
        }
        if (profile == null) {
            LogHelper.warning("Not connected to ServerProfile. May be serverName incorrect?");
        }
        return result;
    }

    @SuppressWarnings("ConfusingArgumentToVarargsMethod")
    public void run(String... args) throws Throwable {
        initGson();
        AuthRequest.registerProviders();
        GetAvailabilityAuthRequest.registerProviders();
        OptionalAction.registerProviders();
        OptionalTrigger.registerProviders();
        if (args.length > 0 && args[0].equals("setup") && !disableSetup) {
            LogHelper.debug("Read ServerWrapperConfig.json");
            loadConfig();
            ServerWrapperSetup setup = new ServerWrapperSetup();
            setup.run();
            System.exit(0);
        }
        LogHelper.debug("Read ServerWrapperConfig.json");
        loadConfig();
        updateLauncherConfig();
        if (config.env != null) Launcher.applyLauncherEnv(config.env);
        else Launcher.applyLauncherEnv(LauncherConfig.LauncherEnvironment.STD);
        if (config.logFile != null) LogHelper.addOutput(IOHelper.newWriter(Paths.get(config.logFile), true));
        {
            restore();
            getProfiles();
        }
        String classname = (config.mainclass == null || config.mainclass.isEmpty()) ? args[0] : config.mainclass;
        if (classname.length() == 0) {
            LogHelper.error("MainClass not found. Please set MainClass for ServerWrapper.json or first commandline argument");
            System.exit(-1);
        }
        if(config.oauth == null && ( config.extendedTokens == null || config.extendedTokens.isEmpty())) {
            LogHelper.error("Auth not configured. Please use 'java -jar ServerWrapper.jar setup'");
            System.exit(-1);
        }
        Class<?> mainClass;
        if (config.classpath != null && !config.classpath.isEmpty()) {
            if (!ServerAgent.isAgentStarted()) {
                LogHelper.warning("JavaAgent not found. Using URLClassLoader");
                URL[] urls = config.classpath.stream().map(Paths::get).map(IOHelper::toURL).toArray(URL[]::new);
                ucp = new PublicURLClassLoader(urls);
                Thread.currentThread().setContextClassLoader(ucp);
                loader = ucp;
            } else {
                LogHelper.info("Found %d custom classpath elements", config.classpath.size());
                for (String c : config.classpath)
                    ServerAgent.addJVMClassPath(c);
            }
        }
        if (config.autoloadLibraries) {
            if (!ServerAgent.isAgentStarted()) {
                throw new UnsupportedOperationException("JavaAgent not found, autoloadLibraries not available");
            }
            if (config.librariesDir == null)
                throw new UnsupportedOperationException("librariesDir is null, autoloadLibraries not available");
            Path librariesDir = Paths.get(config.librariesDir);
            LogHelper.info("Load libraries");
            ServerAgent.loadLibraries(librariesDir);
        }
        if (loader != null) mainClass = Class.forName(classname, true, loader);
        else mainClass = Class.forName(classname);
        MethodHandle mainMethod = MethodHandles.publicLookup().findStatic(mainClass, "main", MethodType.methodType(void.class, String[].class));
        Request.service.reconnectCallback = () ->
        {
            LogHelper.debug("WebSocket connect closed. Try reconnect");
            try {
                Request.reconnect();
                getProfiles();
            } catch (Exception e) {
                LogHelper.error(e);
            }
        };
        LogHelper.info("ServerWrapper: Project %s, LaunchServer address: %s. Title: %s", config.projectname, config.address, Launcher.profile != null ? Launcher.profile.getTitle() : "unknown");
        LogHelper.info("Minecraft Version (for profile): %s", wrapper.profile == null ? "unknown" : wrapper.profile.getVersion().name);
        LogHelper.info("Start Minecraft Server");
        LogHelper.debug("Invoke main method %s", mainClass.getName());
        if (config.args == null) {
            String[] real_args;
            if (args.length > 0) {
                real_args = new String[args.length - 1];
                System.arraycopy(args, 1, real_args, 0, args.length - 1);
            } else real_args = args;

            mainMethod.invoke(real_args);
        } else {
            mainMethod.invoke(config.args.toArray(new String[0]));
        }
    }

    public void updateLauncherConfig() {

        LauncherConfig cfg = new LauncherConfig(config.address, null, null, new HashMap<>(), config.projectname);
        Launcher.setConfig(cfg);
    }

    @Override
    public Config getConfig() {
        return config;
    }

    @Override
    public void setConfig(Config config) {
        this.config = config;
    }

    @Override
    public Config getDefaultConfig() {
        Config newConfig = new Config();
        newConfig.serverName = "your server name";
        newConfig.projectname = "MineCraft";
        newConfig.mainclass = "";
        newConfig.extendedTokens = new HashMap<>();
        newConfig.args = new ArrayList<>();
        newConfig.classpath = new ArrayList<>();
        newConfig.address = "ws://localhost:9274/api";
        newConfig.env = LauncherConfig.LauncherEnvironment.STD;
        return newConfig;
    }

    public static final class Config {
        public String projectname;
        public String address;
        public String serverName;
        public boolean autoloadLibraries;
        public String logFile;
        public List<String> classpath;
        public String librariesDir;
        public String mainclass;
        public List<String> args;
        public String authId;
        public AuthRequestEvent.OAuthRequestEvent oauth;
        public long oauthExpireTime;
        public Map<String, String> extendedTokens;
        public LauncherConfig.LauncherEnvironment env;
    }

    public static final class WebSocketConf {
    }
}
