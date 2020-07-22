package com.linkedin.venice.utils;

import com.google.common.base.Splitter;
import com.linkedin.venice.exceptions.ConfigurationException;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceHttpException;
import com.linkedin.venice.pushmonitor.ExecutionStatus;
import com.linkedin.venice.serialization.avro.AvroProtocolDefinition;
import com.linkedin.venice.serialization.avro.InternalAvroSpecificSerializer;

import java.nio.file.Files;
import java.util.AbstractList;
import java.util.Objects;
import java.util.RandomAccess;
import org.apache.avro.Schema;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.http.HttpStatus;
import org.apache.log4j.Logger;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import static com.linkedin.venice.HttpConstants.*;


/**
 * Helper functions
 */
public class Utils {

  private static Logger LOGGER = Logger.getLogger(Utils.class);

  public static final String WILDCARD_MATCH_ANY = "*";

  /**
   * Print an error and exit with error code 1
   *
   * @param message The error to print
   */
  public static void croak(String message) {
    System.err.println(message);
    System.exit(1);
  }

  /**
   * Print an error and exit with the given error code
   *
   * @param message The error to print
   * @param errorCode The error code to exit with
   */
  public static void croak(String message, int errorCode) {
    System.err.println(message);
    System.exit(errorCode);
  }

  /**
   * A reversed copy of the given list
   *
   * @param <T> The type of the items in the list
   * @param l The list to reverse
   * @return The list, reversed
   */
  public static <T> List<T> reversed(List<T> l) {
    List<T> copy = new ArrayList<T>(l);
    Collections.reverse(copy);
    return copy;
  }

  /**
   * A manual implementation of list equality.
   *
   * This is (unfortunately) useful with Avro lists since they do not work reliably.
   * There are cases where a {@link List<T>} coming out of an Avro record will be
   * implemented as a {@link org.apache.avro.generic.GenericData.Array} and other
   * times it will be a java {@link ArrayList}. When this happens, the equality check
   * fails...
   *
   * @return true if both lists have the same items in the same order
   */
  public static <T> boolean listEquals(List<T> list1, List<T> list2) {
    if (list1.size() != list2.size()) {
      return false;
    } else {
      for (int i = 0; i < list2.size(); i++) {
        if (!list1.get(i).equals(list2.get(i))) {
          return false;
        }
      }
    }
    return true;
  }

  /**
   * Throw an IllegalArgumentException if the argument is null, otherwise just
   * return the argument.
   *
   * @param t The thing to check for nullness.
   * @param message The message to put in the exception if it is null
   * @param <T> The type of the thing
   * @return t
   */
  public static <T> T notNull(T t, String message) {
    if (t == null) {
      throw new IllegalArgumentException(message);
    }
    return t;
  }

  /**
   * Throw an IllegalArgumentException if the argument is null, otherwise just
   * return the argument.
   *
   * Useful for assignment as in this.thing = Utils.notNull(thing);
   *
   * @param t  The thing to check for nullness.
   * @param <T>  The type of the thing
   * @return t
   */
  public static <T> T notNull(T t) {
    if (t == null) {
      throw new IllegalArgumentException("This object MUST be non-null.");
    }
    return t;
  }

  /**
   *  Given a filePath, reads into a Venice Props object
   *  @param configFileName - String path to a properties file
   *  @return A @Props object with the given configurations
   * */
  public static VeniceProperties parseProperties(String configFileName) throws IOException {
    Properties props = new Properties();
    try (FileInputStream inputStream = new FileInputStream(configFileName)) {
      props.load(inputStream);
    }
    return new VeniceProperties(props);
  }

  /**
   * Generate VeniceProperties object from a given directory, file.
   *
   * @param directory directory that contains the Property file
   * @param fileName fileName of the Property file
   * @param isFileOptional set this to true if the file is optional. If
   *                       file is missing and set to true, empty property
   *                       will be returned. If file is missing and set
   *                       to false, this will throw an exception.
   * @return
   * @throws Exception
   */
  public static VeniceProperties parseProperties(String directory, String fileName, boolean isFileOptional)
      throws IOException {
    String propsFilePath = directory + File.separator + fileName;

    File propsFile = new File(propsFilePath);
    boolean fileExists = propsFile.exists();
    if (!fileExists) {
      if (isFileOptional) {
        return new VeniceProperties(new Properties());
      } else {
        String fullFilePath = Utils.getCanonicalPath(propsFilePath);
        throw new ConfigurationException(fullFilePath + " does not exist.");
      }
    }

    if (!Utils.isReadableFile(propsFilePath)) {
      String fullFilePath = Utils.getCanonicalPath(propsFilePath);
      throw new ConfigurationException(fullFilePath + " is not a readable configuration file.");
    }

    return Utils.parseProperties(propsFilePath);
  }

  /**
   * Given a .property file, reads into a Venice Props object
   * @param propertyFile The .property file
   * @return A @Props object with the given properties
   * @throws Exception  if File not found or not accessible
   */
  public static VeniceProperties parseProperties(File propertyFile) throws IOException {
    Properties props = new Properties();
    FileInputStream inputStream = null;
    try {
      inputStream = new FileInputStream(propertyFile);
      props.load(inputStream);
    } finally {
      if (inputStream != null) {
        inputStream.close();
      }
    }
    return new VeniceProperties(props);
  }

  /**
   * Check if a directory exists and is readable
   *
   * @param d  The directory
   * @return true iff the argument is a readable directory
   */
  public static boolean isReadableDir(File d) {
    return d.exists() && d.isDirectory() && d.canRead();
  }

  /**
   * Check if a directory exists and is readable
   *
   * @param dirName The directory name
   * @return true iff the argument is the name of a readable directory
   */
  public static boolean isReadableDir(String dirName) {
    return isReadableDir(new File(dirName));
  }

  /**
   * Check if a file exists and is readable
   *
   * @param fileName
   * @return true iff the argument is the name of a readable file
   */
  public static boolean isReadableFile(String fileName) {
    return isReadableFile(new File(fileName));
  }

  /**
   * Check if a file exists and is readable
   * @param f The file
   * @return true iff the argument is a readable file
   */
  public static boolean isReadableFile(File f) {
    return f.exists() && f.isFile() && f.canRead();
  }

  /**
   * Get the full Path of the file. Useful in logging/error output
   *
   * @param fileName
   * @return canonicalPath of the file.
   */
  public static String getCanonicalPath(String fileName) {
    try {
      return new File(fileName).getCanonicalPath();
    } catch (IOException ex) {
      return fileName;
    }
  }

  public static boolean directoryExists(String dataDirectory) {
    return Files.isDirectory(Paths.get(dataDirectory));
  }

  private static boolean localhost = false;

  /**
   * The ssl certificate we have for unit tests has the hostname "localhost".  Any tests that rely on this certificate
   * require that the hostname of the machine match the hostname of the certificate.  This method lets us globally assert
   * that the hostname for the machine should resolve to "localhost".  We can call this method at the start of any
   * tests that require hostnames to resolve to "localhost"
   *
   * It's not ideal to put this as state in a Utils class, we can revisit if we come up with a better way to do it
   */
  public static void thisIsLocalhost() {
    localhost = true;
  }

  /**
   * Get the node's host name.
   * @return current node's host name.
   */
  public static String getHostName() {

    if (localhost) {
      return LOCALHOST;
    }

    String hostName;

    try {
      hostName = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException e) {
      e.printStackTrace();
      throw new VeniceException("Unable to get the hostname.", e);
    }

    if (StringUtils.isEmpty(hostName)) {
      throw new VeniceException("Unable to get the hostname.");
    }

    return hostName;
  }

  /***
   * Sleep until number of milliseconds have passed, or the operation is interrupted.  This method will swallow the
   * InterruptedException and terminate, if this is used in a loop it may become difficult to cleanly break out
   * of the loop.
   *
   * @param millis
   */
  public static void sleep(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  public static boolean isNullOrEmpty(String value) {
    return value == null || value.length() == 0;
  }

  public static int parseIntFromString(String value, String fieldName) {
    try {
      return Integer.parseInt(value);
    } catch (NumberFormatException e) {
      throw new VeniceHttpException(HttpStatus.SC_BAD_REQUEST, fieldName + " must be an integer, but value: " + value, e);
    }
  }

  public static long parseLongFromString(String value, String fieldName) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new VeniceHttpException(HttpStatus.SC_BAD_REQUEST, fieldName + " must be a long, but value: " + value, e);
    }
  }

  /**
   * Since {@link Boolean#parseBoolean(String)} does not throw exception and will always return 'false' for
   * any string that are not equal to 'true', We validate the string by our own.
   */
  public static boolean parseBooleanFromString(String value, String fieldName) {
    if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")) {
      return Boolean.valueOf(value);
    } else {
      throw new VeniceHttpException(HttpStatus.SC_BAD_REQUEST, fieldName + " must be a boolean, but value: " + value);
    }
  }

  /**
   * For String-String key-value map config, we expect the command-line interface users to use "key1=value1,key2=value2,..."
   * format to represent it. This method deserialize it to String-String map.
   */
  public static Map<String, String> parseCommaSeparatedStringMapFromString(String value, String fieldName) {
    try {
      return Splitter.on(",").withKeyValueSeparator("=").split(value);
    } catch (Exception e) {
      throw new VeniceException(fieldName + " must be key value pairs separated by comma, but value: " + value);
    }
  }

  public static Map<CharSequence, CharSequence> getCharSequenceMapFromStringMap(Map<String, String> stringStringMap) {
    return new HashMap<>(stringStringMap);
  }

  public static Map<String, String> getStringMapFromCharSequenceMap(Map<CharSequence, CharSequence> charSequenceMap) {
    if (charSequenceMap == null) {
      return null;
    }

    Map<String, String> ssMap = new HashMap<>();
    charSequenceMap.forEach((key, value) -> ssMap.put(key.toString(), value.toString()));
    return ssMap;
  }

  public static String getHelixNodeIdentifier(int port) {
    return Utils.getHostName() + "_" + port;
  }

  public static String parseHostFromHelixNodeIdentifier(String nodeId) {
    return nodeId.substring(0, nodeId.lastIndexOf('_'));
  }

  public static int parsePortFromHelixNodeIdentifier(String nodeId) {
    return parseIntFromString(nodeId.substring(nodeId.lastIndexOf('_') + 1), "port");
  }

  /**
   * Utility function to get schemas out of embedded resources.
   *
   * @param resourcePath The path of the file under the src/main/resources directory
   * @return the {@link org.apache.avro.Schema} instance corresponding to the file at {@param resourcePath}
   * @throws IOException
   */
  public static Schema getSchemaFromResource(String resourcePath) throws IOException {
    ClassLoader classLoader = Utils.class.getClassLoader();
    InputStream inputStream = classLoader.getResourceAsStream(resourcePath);
    if (inputStream == null) {
      throw new IOException("Resource path '" + resourcePath + "' does not exist!");
    }
    String schemaString = IOUtils.toString(inputStream);
    Schema schema = Schema.parse(schemaString);
    LOGGER.info("Loaded schema from resource path '" + resourcePath + "'");
    LOGGER.debug("Schema literal:\n" + schema.toString(true));
    return schema;
  }

  public static Map<Integer, Schema> getAllSchemasFromResources(AvroProtocolDefinition protocolDef) {
    final int SENTINEL_PROTOCOL_VERSION_USED_FOR_UNDETECTABLE_COMPILED_SCHEMA =
        InternalAvroSpecificSerializer.SENTINEL_PROTOCOL_VERSION_USED_FOR_UNDETECTABLE_COMPILED_SCHEMA;
    final int SENTINEL_PROTOCOL_VERSION_USED_FOR_UNVERSIONED_PROTOCOL =
        InternalAvroSpecificSerializer.SENTINEL_PROTOCOL_VERSION_USED_FOR_UNVERSIONED_PROTOCOL;
    int currentProtocolVersion;
    if (protocolDef.currentProtocolVersion.isPresent()) {
      int currentProtocolVersionAsInt = protocolDef.currentProtocolVersion.get();
      if (currentProtocolVersionAsInt == SENTINEL_PROTOCOL_VERSION_USED_FOR_UNDETECTABLE_COMPILED_SCHEMA ||
          currentProtocolVersionAsInt == SENTINEL_PROTOCOL_VERSION_USED_FOR_UNVERSIONED_PROTOCOL ||
          currentProtocolVersionAsInt > Byte.MAX_VALUE) {
        throw new IllegalArgumentException("Improperly defined protocol! Invalid currentProtocolVersion: " + currentProtocolVersionAsInt);
      }
      currentProtocolVersion = currentProtocolVersionAsInt;
    } else {
      currentProtocolVersion = SENTINEL_PROTOCOL_VERSION_USED_FOR_UNVERSIONED_PROTOCOL;
    }

    byte compiledProtocolVersion = SENTINEL_PROTOCOL_VERSION_USED_FOR_UNDETECTABLE_COMPILED_SCHEMA;
    String className = protocolDef.getClassName();
    Map<Integer, Schema> protocolSchemaMap = new TreeMap<>();
    int initialVersion;
    if (currentProtocolVersion > 0) {
      initialVersion = 1; // TODO: Consider making configurable if we ever need to fully deprecate some old versions
    } else {
      initialVersion = currentProtocolVersion;
    }
    final String sep = "/"; // TODO: Make sure that jar resources are always forward-slash delimited, even on Windows
    int version = initialVersion;
    while (true) {
      String versionPath = "avro" + sep + className + sep;
      if (currentProtocolVersion != SENTINEL_PROTOCOL_VERSION_USED_FOR_UNVERSIONED_PROTOCOL) {
        versionPath += "v" + version + sep;
      }
      versionPath += className + ".avsc";
      try {
        Schema schema = Utils.getSchemaFromResource(versionPath);
        protocolSchemaMap.put(version, schema);
        if (schema.equals(protocolDef.getCurrentProtocolVersionSchema())) {
          compiledProtocolVersion = (byte) version;
          break;
        }
        if (currentProtocolVersion == SENTINEL_PROTOCOL_VERSION_USED_FOR_UNVERSIONED_PROTOCOL) {
          break;
        } else if (currentProtocolVersion > 0) {
          // Positive version protocols should continue looking "up" for the next version
          version++;
        } else {
          // And vice-versa for negative version protocols
          version--;
        }
      } catch (IOException e) {
        // Then the schema was not found at the requested path
        if (version == initialVersion) {
          throw new VeniceException("Failed to initialize schemas! No resource found at: " + versionPath, e);
        } else {
          break;
        }
      }
    }

    /** Ensure that we are using Avro properly. */
    if (compiledProtocolVersion == SENTINEL_PROTOCOL_VERSION_USED_FOR_UNDETECTABLE_COMPILED_SCHEMA) {
      throw new VeniceException("Failed to identify which version is currently compiled for " + protocolDef.name() +
          ". This could happen if the avro schemas have been altered without recompiling the auto-generated classes" +
          ", or if the auto-generated classes were edited directly instead of generating them from the schemas.");
    }

    /**
     * Verify that the intended current protocol version defined in the {@link AvroProtocolDefinition} is available
     * in the jar's resources and that it matches the auto-generated class that is actually compiled.
     *
     * N.B.: An alternative design would have been to assume that what is compiled is the intended version, but we
     * are instead making this a very explicit choice by requiring the change in both places and failing loudly
     * when there is an inconsistency.
     */
    Schema intendedCurrentProtocol = protocolSchemaMap.get((int) currentProtocolVersion);
    if (null == intendedCurrentProtocol) {
      throw new VeniceException("Failed to get schema for current version: " + currentProtocolVersion
          + " class: " + className);
    } else if (!intendedCurrentProtocol.equals(protocolDef.getCurrentProtocolVersionSchema())) {
      throw new VeniceException("The intended protocol version (" + currentProtocolVersion +
          ") does not match the compiled protocol version (" + compiledProtocolVersion + ").");
    }

    return protocolSchemaMap;
  }

  /**
   * Verify that is the new status allowed to be used.
   */
  public static boolean verifyTransition(ExecutionStatus newStatus, ExecutionStatus... allowed) {
    return Arrays.asList(allowed).contains(newStatus);
  }

  public static List<String> parseCommaSeparatedStringToList(String rawString) {
    String[] strArray = rawString.split(",");
    if (strArray.length < 1) {
      throw new VeniceException("Invalid input: " + rawString);
    }
    return Arrays.asList(strArray);
  }

  /**
   * Computes the percentage, taking care of division by 0
   */
  public static double getRatio(long rawNum, long total) {
    return total == 0 ? 0.0d : rawNum / (double) total;
  }

  /**
   * @param value the double value to be rounded
   * @param precision the number of decimal places by which to round
   * @return {@param value} rounded by {@param precision} decimal places
   */
  public static double round(double value, int precision) {
    int scale = (int) Math.pow(10, precision);
    return (double) Math.round(value * scale) / scale;
  }

  private static final String[] LARGE_NUMBER_SUFFIXES = {"", "K", "M", "B", "T"};

  public static String makeLargeNumberPretty(long largeNumber) {
    if (largeNumber < 2) {
      return "" + largeNumber;
    }
    double doubleNumber = (double) largeNumber;
    double numberOfDigits = Math.ceil(Math.log10(doubleNumber));
    int suffixIndex = Math.min((int) Math.floor((numberOfDigits - 1) / 3), LARGE_NUMBER_SUFFIXES.length - 1);
    double divider = Math.pow(1000, suffixIndex);
    int prettyNumber = (int) Math.round(doubleNumber / divider);
    return prettyNumber + LARGE_NUMBER_SUFFIXES[suffixIndex];
  }

  private static class TimeUnitInfo {
    String suffix;
    int multiplier;
    DecimalFormat format;

    public TimeUnitInfo(String suffix, int multiplier, DecimalFormat format) {
      this.suffix = suffix;
      this.multiplier = multiplier;
      this.format = format;
    }
  }

  private static final Pair<String, Integer>[] TIME_SUFFIX_AND_MULTIPLIER = Arrays.asList(
      new Pair<>("ns", 1),
      new Pair<>("us", Time.NS_PER_US),
      new Pair<>("ms", Time.US_PER_MS),
      new Pair<>("s", Time.MS_PER_SECOND),
      new Pair<>("m", Time.SECONDS_PER_MINUTE),
      new Pair<>("h", Time.MINUTES_PER_HOUR)
  ).toArray(new Pair[6]);


  private static final TimeUnitInfo[] TIME_UNIT_INFO = {
      new TimeUnitInfo("ns", 1, new DecimalFormat("0")),
      new TimeUnitInfo("us", Time.NS_PER_US, new DecimalFormat("0")),
      new TimeUnitInfo("ms", Time.US_PER_MS, new DecimalFormat("0")),
      new TimeUnitInfo("s", Time.MS_PER_SECOND, new DecimalFormat("0.0")),
      new TimeUnitInfo("m", Time.SECONDS_PER_MINUTE, new DecimalFormat("0.0")),
      new TimeUnitInfo("h", Time.MINUTES_PER_HOUR, new DecimalFormat("0.0")),
  };

  public static String makeTimePretty(long nanoSecTime) {
    double formattedTime = nanoSecTime;
    int timeUnitIndex = 0;
    while (timeUnitIndex < TIME_UNIT_INFO.length - 1) {
      int nextMultiplier = TIME_UNIT_INFO[timeUnitIndex + 1].multiplier;
      if (formattedTime < nextMultiplier) {
        break;
      } else {
        formattedTime = formattedTime / (double) nextMultiplier;
        timeUnitIndex++;
      }
    }
    DecimalFormat df = TIME_UNIT_INFO[timeUnitIndex].format;
    return df.format(formattedTime) + TIME_UNIT_INFO[timeUnitIndex].suffix;
  }

  public static String getCurrentWorkingDirectory() {
    Path currentRelativePath = Paths.get("");
    return currentRelativePath.toAbsolutePath().toString();
  }

  /**
   * Note: this may fail in some JVM implementations.
   *
   * Lifted from: https://stackoverflow.com/a/7690178
   *
   * @return the pid of the current Java process, or "N/A" if unavailable
   */
  public static String getPid() {
    try {
      // something like '<pid>@<hostname>', at least in SUN / Oracle JVMs
      final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
      final int index = jvmName.indexOf('@');

      if (index < 1) {
        LOGGER.warn("Failed to determine pid");
        return "N/A";
      }

      return Long.toString(Long.parseLong(jvmName.substring(0, index)));
    } catch (Exception e) {
      LOGGER.warn("Failed to determine pid", e);
      return "N/A";
    }
  }

  /**
   * This might not work when application is running inside application server like Jetty
   *
   * @return the version of the venice-common jar on the classpath, if available, or "N/A" otherwise.
   */
  public static String getVeniceVersionFromClassPath() {
    //The application class loader is no longer an instance of java.net.URLClassLoader in JDK 9+
    //So changing implementation to be compatible with JDK8 and JDK11 at the same time
    String classpath = System.getProperty("java.class.path");
    String[] entries = classpath.split(File.pathSeparator);
    String jarNamePrefixToLookFor = "venice-common-";

    for(int i = 0; i < entries.length; i++) {
      int indexOfJarName = entries[i].lastIndexOf(jarNamePrefixToLookFor);
      if (indexOfJarName > -1) {
        int substringStart = indexOfJarName + jarNamePrefixToLookFor.length();
        return entries[i].substring(substringStart).replace(".jar", "");
      }
    }

    LOGGER.warn("Failed to determine Venice version");
    return "N/A";
  }

  public static String getCurrentUser() {
    try {
      return System.getProperty("user.name");
    } catch (Exception e) {
      LOGGER.warn("Failed to determine current user");
      return "N/A";
    }
  }

  public static Map<CharSequence, CharSequence> getDebugInfo() {
    Map<CharSequence, CharSequence> debugInfo = new HashMap<>();
    try {
      debugInfo.put("host", getHostName());
    } catch (Exception e) {
      LOGGER.warn("Failed to determine host name");
      debugInfo.put("host", "N/A");
    }
    debugInfo.put("path", getCurrentWorkingDirectory());
    debugInfo.put("pid", getPid());
    debugInfo.put("version", getVeniceVersionFromClassPath());
    debugInfo.put("user", getCurrentUser());

    return debugInfo;
  }

  public static List<Float> asUnmodifiableList(float[] array)
  {
    Objects.requireNonNull(array);
    class ResultList extends AbstractList<Float> implements RandomAccess
    {
      @Override
      public Float get(int index)
      {
        return array[index];
      }

      @Override
      public int size()
      {
        return array.length;
      }
    };
    return new ResultList();
  }
}
