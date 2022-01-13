package com.contrastsecurity;

import java.io.Console;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;
import sun.jvmstat.monitor.MonitoredHost;

import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.agent.ByteBuddyAgent.ProcessProvider;
import picocli.CommandLine;
import picocli.CommandLine.Command;

public class Jbom implements Runnable {

    @CommandLine.Command(name = "list", description = "List all JVM PIDs" )
    
    @CommandLine.Option(names = { "-h", "--host" }, description = "Hostname or IP address to connect to")
    private String host = null;

    @CommandLine.Option(names = { "-u", "--user" }, description = "Username of user to connect as")
    private String user;

    @CommandLine.Option(names = { "-p", "--pass" }, description = "Password for user" )
    private String pass;

    @CommandLine.Option(names = { "-r", "--remote" }, defaultValue = "/tmp/jbom", description = "Remote directory to use" )
    private String remoteDir = "/tmp/jbom";

    @CommandLine.Option(names = { "-j", "--jvmpid" }, defaultValue = "all", description = "JVM PID to attach to or 'all'" )
    private String pid = "all";

    @CommandLine.Option(names = { "-x", "--exclude" }, description = "JVM PID to exclude" )
    private String exclude;

    @CommandLine.Option(names = { "-f", "--file" }, description = "File to be scanned" )
    private String file;

    @CommandLine.Option(names = { "-d", "--dir" }, description = "Directory to be scanned" )
    private String dir;

    @CommandLine.Option(names = { "-o", "--outputDir" }, description = "Output directory" )
    private String outputDir = System.getProperty("user.dir") + "/sbom";

    @CommandLine.Option(names = { "-t", "--tag" }, description = "Tag to use in output filenames" )
    private String tag;

    
    public static void main(String[] args){
        int exitCode = new CommandLine(new Jbom()).execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {

        Jbom jbom = new Jbom();
        jbom.printBanner();

        if ( host != null ) {
            jbom.doRemote( pid, exclude, outputDir, host, user, pass, remoteDir );
        }

        else if ( file != null ) {
            jbom.doFile( file, outputDir );
        }

        else if ( dir != null ) {
            jbom.doDirectory( dir, outputDir );
        }
        
        else {
            jbom.doLocal( pid, exclude, outputDir, tag );
        }

        Logger.log( "" );
        Logger.log( "jbom complete" );
    }

    public void doLocal(String pid, String exclude, String outputDir, String tag) {
        ensureToolsJar();
        if ( pid.equals( "all" ) ) {
            try {
                Map<String, String> processes = getProcesses( exclude );
                if ( processes.isEmpty() ) {
                    Logger.log( "No local Java process detected" );
                } else {
                    Logger.log( "Detected "+processes.size()+" local Java processes:" );
                    int count = 1;
                    for ( String procid : processes.keySet() ) {
                        Logger.log( "  " + procid + " (" + processes.get( procid ) + ")" );
                    }
                
                    Logger.log( "" );
                    Logger.log( "Starting analysis..." );
                    count = 1;
                    for( String procid : processes.keySet() ) {
                        Logger.log( "" );
                        Logger.log( "  " + count++ + ": " + procid + " (" + processes.get( procid ) + ")" );
                        String name = outputDir + "/jbom-" + procid + ".json";
                        generateBOM( procid, name );
                    }
                }
            } catch( Exception e ) {
                e.printStackTrace();
            }
        } else {
            Logger.log( "Analyzing local Java process with pid " + pid );
            String name = outputDir + "/jbom-" + ( tag == null ? "" : "-" +tag ) + "-" + pid + ".json";
            generateBOM( pid, name);
        }
    }

    public Libraries doFile(String file, String outputDir) {
        Logger.log( "Analyzing file " + file );
        Libraries libs = new Libraries();

        File f = new File( file );
        if ( !f.exists() ) {
            Logger.log( "Could not find file: " + file );
        }
        if ( !f.isFile() ) {
            Logger.log( "Could not open file: " + file );
        }
        if ( !libs.isArchive( file ) ) {
            Logger.log( "File does not appear to be an archive: " + file );
        }

        try{
            String name = file.substring( 0, file.lastIndexOf('.'));
            name = outputDir + "/jbom-" + name + ( tag == null ? "" : "-" +tag ) + ".json";
            libs.runScan( f );
            libs.save(name);
        }catch(Exception e){
            e.printStackTrace();
        }
        return libs;
    }

    public Libraries doDirectory(String dir, String outputDir) {
        Logger.log( "Analyzing directory " + dir );
        Libraries libs = new Libraries();
        Path path = Paths.get( dir );
        String dirname = path.getFileName().toString();

        try {
            String name = outputDir + "/jbom-" + dirname + ( tag == null ? "" : "-" +tag ) + ".json";
            List<Path> paths = listFiles(path);
            for ( Path p : paths ) {
                try {
                    libs.runScan( p.toFile() );
                } catch( Exception e ) {
                    Logger.log( "  Problem processing local file " + p + ". Continuing...");
                }
            }
            libs.save(name);
        } catch( Exception e ) {
            Logger.log( "  Couldn't get a list of files in " + dir );
        }
        return libs;
    }


    // list all files from this path
    public static List<Path> listFiles(Path path) throws IOException {
        List<Path> result;
        try (Stream<Path> walk = Files.walk(path)) {
            result = walk.filter(Files::isRegularFile)
                    .collect(Collectors.toList());
        }
        return result;
    }


    public void doRemote(String pid, String exclude, String outputDir, String host, String user, String pass, String remoteDir) {
        Logger.log( "Analyzing remote JVMs on " + host );
        java.io.Console console = System.console();
        if ( user == null ) {
            console.readLine("Username: ");
        }
        if ( pass == null ) {
            pass = new String(console.readPassword("Password: "));
        }

        try {
            Remote remote = new Remote( host, user, pass );

            // 1. upload the jbom.jar file
            String filename = Remote.class.getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI()
                .getPath();
            File agentFile = new File(filename);
            remote.upload( remoteDir, agentFile );
            
            // 2. run java -jar jbom.jar on remote server
            Logger.log( "Connecting to " + host );
            String myPid = ByteBuddyAgent.ProcessProvider.ForCurrentVm.INSTANCE.resolve();
            remote.exec( "java -jar " + agentFile.getAbsolutePath() + " -x " + myPid + " -o " + remoteDir + " -p " + tag );

            // 3. download results and cleanup
            File odir = new File( outputDir );
            if ( !odir.exists() || !odir.isDirectory() ) {
                odir.mkdirs();
            }
            List<String> files = remote.download( host, remoteDir, outputDir );
            Logger.log( "Detected " + files.size() + " remote Java process" + ( files.size() > 1 ? "es" : "" ) );
            for ( String file : files ) {
                Logger.log( "  - " + file );
            }
            Logger.log( "Remote Java process analysis complete" );
            Logger.log( "Saving SBOMs for " + host + " to directory: " + outputDir );

        } catch ( Exception e ) {
            e.printStackTrace();
        }
    }


    public void listProcesses() throws Exception {
        Map<String, String> processes = getProcesses( null );
        for( String pid : processes.keySet() ) {
            System.out.println(pid + " \t" + processes.get(pid));
        }
    }

    public Map<String, String> getProcesses( String exclude ) throws Exception {
        Map<String,String> map = new HashMap<String, String>();
        List<VirtualMachineDescriptor> vms = VirtualMachine.list();
        vms.stream()
            .filter( vm -> 
                !vm.id().equals( exclude ) &&
                !vm.displayName().contains("jbom") &&
                !vm.displayName().contains(".vscode")
                )
            .forEach(vm -> {
                map.put(vm.id(), vm.displayName());
            });
        return map;
    }

    public static void ensureToolsJar() {
		if (Jbom.class.getResource("/sun.jvmstat.monitor.MonitoredVm".replace('.', '/') + ".class") == null) {
            try {
                String javaHome = System.getProperty("java.home");
                String toolsJarURL = "file:" + javaHome + "/../lib/tools.jar";

                // Make addURL accessible
                Method method = URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
                method.setAccessible(true);

                URLClassLoader sysloader = (URLClassLoader)ClassLoader.getSystemClassLoader();
                if (sysloader.getResourceAsStream("/com/sun/tools/attach/VirtualMachine.class") == null) {
                    method.invoke(sysloader, (Object) new URL(toolsJarURL));
                    Thread.currentThread().getContextClassLoader().loadClass("com.sun.tools.attach.VirtualMachine");
                    Thread.currentThread().getContextClassLoader().loadClass("com.sun.tools.attach.AttachNotSupportedException");
                }

            } catch (Exception e) {
                System.out.println("Java home points to " + System.getProperty("java.home") + " make sure it is not a JRE path");
                e.printStackTrace();
            }
		}
    }


    public void generateBOM( String pid, String path) {

        String myPid = ByteBuddyAgent.ProcessProvider.ForCurrentVm.INSTANCE.resolve();
        if ( pid.equals( myPid ) ) {
            Logger.log( "     Skipping jbom process (pid: "+myPid+")" );
            return;
        }

        if (pid != null && !pid.isEmpty() ) {
            try{
                Logger.log( "     Analyzing..." );
                String filename = Jbom.class.getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI()
                        .getPath();
                File agentFile = new File(filename);
                ByteBuddyAgent.attach(agentFile.getAbsoluteFile(), pid, path);
            } catch(Exception e) {
                Logger.log( "     Error attaching to " + pid );
                Logger.log ( "      --> " + e.getMessage() );
                e.printStackTrace();
            }
        }
        Logger.log( "     Saving SBOM to " + path );
    }

    private void printBanner() {
        Logger.log( "" );
        Logger.log( "                      _ __" );
        Logger.log( "                     (_) /_  ____  ____ ___" );
        Logger.log( "                    / / __ \\/ __ \\/ __ `__ \\" );
        Logger.log( "                   / / /_/ / /_/ / / / / / /" );
        Logger.log( "                __/ /_.___/\\____/_/ /_/ /_/" );
        Logger.log( "               /___/" );
        Logger.log( "" );
        Logger.log( "     by Contrast Security - https://contrastsecurity.com" );
        Logger.log( "" );
        Logger.log( "      jbom generates SBOMs for all JVMs running on a host" );
        Logger.log( "         https://github.com/Contrast-Security-OSS/jbom" );
        Logger.log( "" );
    }

}
