package io.everytrade.server.plugin;

import io.everytrade.server.plugin.api.IPlugin;
import io.everytrade.server.plugin.api.connector.ConnectorDescriptor;
import io.everytrade.server.plugin.api.connector.ConnectorParameterDescriptor;
import io.everytrade.server.plugin.api.connector.DownloadResult;
import io.everytrade.server.plugin.api.connector.IConnector;
import io.everytrade.server.plugin.api.parser.ConversionStatistic;
import io.everytrade.server.plugin.api.parser.ImportedTransactionBean;
import io.everytrade.server.plugin.api.parser.ParseResult;
import io.everytrade.server.plugin.support.EverytradePluginManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

public class Tester {
    public static final String TEMPLATE_FILE_SUFFIX = ".template";
    private static final String EMULATED_EVERYTRADE_VERSION = "20201014";
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private final Path workDir;
    private final Path pluginDir;

    public Tester(Path workDir) {
        this.workDir = workDir;
        final Properties properties = loadProperties("tester.properties").orElse(new Properties());
        pluginDir = Paths.get(
            properties.getProperty("tester.pluginDir", "plugin-tester/build/testedPlugins")
        );
    }

    public Optional<Properties> loadProperties(String fileName) {
        try {
            final Properties properties = new Properties();
            properties.load(new FileInputStream(workDir.resolve(fileName).toFile()));
            return Optional.of(properties);
        } catch (FileNotFoundException e) {
            return Optional.empty();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static void main(String[] args) {
        new Tester(
            Paths.get(
                args.length == 1 ? args[0] : ""
            )
        ).test();
    }

    private void test() {
        final EverytradePluginManager pluginManager =
            new EverytradePluginManager(pluginDir);

        pluginManager.setSystemVersion(String.format("%s.0.0", EMULATED_EVERYTRADE_VERSION));
        pluginManager.loadPlugins();
        pluginManager.startPlugins();
        final List<IPlugin> plugins = pluginManager.getExtensions(IPlugin.class);

        for (IPlugin plugin : plugins) {
            testPlugin(plugin);
        }
    }

    private void testPlugin(IPlugin plugin) {
        log.info("plugin = " + plugin.getId());
        final List<ConnectorDescriptor> connectorDescriptors = plugin.allConnectorDescriptors();
        for (ConnectorDescriptor connectorDescriptor : connectorDescriptors) {
            log.info("connectorDescriptor = " + connectorDescriptor);
            writeParamsTemplate(connectorDescriptor);
            final Optional<Map<String, String>> parameters = loadParams(connectorDescriptor.getId());
            if (parameters.isEmpty()) {
                continue;
            }
            final IConnector connector = plugin.createConnectorInstance(connectorDescriptor.getId(), parameters.get());

            //first connection
            final DownloadResult downloadResult = connector.getTransactions(null);
            printResult(downloadResult);

            //follow-up connection
            printResult(connector.getTransactions(downloadResult.getLastDownloadedTransactionId()));
        }
    }

    private void writeParamsTemplate(ConnectorDescriptor descriptor) {
        final Properties parameters = new Properties();
        for (ConnectorParameterDescriptor parameter : descriptor.getParameters()) {
            parameters.setProperty(parameter.getId(), parameter.getDescription());
        }
        final String fileName = String.format("%s%s", getParamsFileName(descriptor.getId()), TEMPLATE_FILE_SUFFIX);
        try (final FileWriter writer = new FileWriter(fileName)) {
            parameters.store(
                writer,
                String.format(
                    "Fill in proper values and remove the '%s' file name suffix to test the corresponding connector.",
                    TEMPLATE_FILE_SUFFIX
                )
            );
        } catch (IOException e) {
            log.error("Error writing parameter template file for connector '{}'.", descriptor.getId(), e);
        }
    }

    private void printResult(DownloadResult downloadResult) {
        final ParseResult parseResult = downloadResult.getParseResult();

        StringBuilder stringBuilder = new StringBuilder();
        final String columnSeparator = ",";
        final String lineSeparator = "\n";
        stringBuilder
            .append("uid").append(columnSeparator)
            .append("executed").append(columnSeparator)
            .append("base").append(columnSeparator)
            .append("quote").append(columnSeparator)
            .append("action").append(columnSeparator)
            .append("baseQuantity").append(columnSeparator)
            .append("unitPrice").append(columnSeparator)
            .append("transactionPrice").append(columnSeparator)
            .append("feeQuote").append(lineSeparator);

        for (ImportedTransactionBean b : parseResult.getImportedTransactionBeans()) {
            stringBuilder
                .append(b.getUid()).append(columnSeparator)
                .append(b.getExecuted()).append(columnSeparator)
                .append(b.getBase()).append(columnSeparator)
                .append(b.getQuote()).append(columnSeparator)
                .append(b.getAction()).append(columnSeparator)
                .append(b.getBaseQuantity()).append(columnSeparator)
                .append(b.getUnitPrice()).append(columnSeparator)
                .append(b.getTransactionPrice()).append(columnSeparator)
                .append(b.getFeeQuote()).append(lineSeparator);
        }
        log.info("importedTransactionBeans = \n" + stringBuilder.toString());

        final ConversionStatistic conversionStatistic = parseResult.getConversionStatistic();
        log.info("conversionStatistic = " + conversionStatistic);

        final String lastDownloadedTransactionId = downloadResult.getLastDownloadedTransactionId();
        log.info("lastDownloadedTransactionId = " + lastDownloadedTransactionId);
    }

    private Optional<Map<String, String>> loadParams(String id) {
        final Optional<Properties> properties = loadProperties(getParamsFileName(id));
        return properties.map(this::toMap);
    }

    private String getParamsFileName(String id) {
        return String.format("private/%s.properties", id);
    }

    private Map<String, String> toMap(Properties properties) {
        final Map<String, String> ret = new HashMap<>();
        for (String propertyName : properties.stringPropertyNames()) {
            ret.put(propertyName, properties.getProperty(propertyName));
        }
        return ret;
    }
}