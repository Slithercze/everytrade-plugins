package io.everytrade.server.plugin.impl.everytrade;

import io.everytrade.server.model.SupportedExchange;
import io.everytrade.server.parser.exchange.XChangeApiTransactionBean;
import io.everytrade.server.plugin.api.IPlugin;
import io.everytrade.server.plugin.api.connector.ConnectorDescriptor;
import io.everytrade.server.plugin.api.connector.ConnectorParameterDescriptor;
import io.everytrade.server.plugin.api.connector.ConnectorParameterType;
import io.everytrade.server.plugin.api.connector.DownloadResult;
import io.everytrade.server.plugin.api.connector.IConnector;
import io.everytrade.server.plugin.api.parser.ConversionStatistic;
import io.everytrade.server.plugin.api.parser.ImportedTransactionBean;
import io.everytrade.server.plugin.api.parser.ParseResult;
import io.everytrade.server.plugin.api.parser.RowError;
import io.everytrade.server.plugin.api.parser.RowErrorType;
import org.knowm.xchange.Exchange;
import org.knowm.xchange.ExchangeFactory;
import org.knowm.xchange.ExchangeSpecification;
import org.knowm.xchange.bittrex.BittrexExchange;
import org.knowm.xchange.dto.trade.UserTrade;
import org.knowm.xchange.service.trade.TradeService;
import org.knowm.xchange.service.trade.params.TradeHistoryParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class BittrexConnector implements IConnector {
    private final Logger log = LoggerFactory.getLogger(this.getClass());
    private static final String ID = EveryTradePlugin.ID + IPlugin.PLUGIN_PATH_SEPARATOR + "bittrexApiConnector";

    private static final ConnectorParameterDescriptor PARAMETER_API_SECRET =
        new ConnectorParameterDescriptor(
            "apiSecret",
            ConnectorParameterType.SECRET,
            "API Secret",
            ""
        );

    private static final ConnectorParameterDescriptor PARAMETER_API_KEY =
        new ConnectorParameterDescriptor(
            "apiKey",
            ConnectorParameterType.STRING,
            "API Key",
            ""
        );

    public static final ConnectorDescriptor DESCRIPTOR = new ConnectorDescriptor(
        ID,
        "Bittrex Connector",
        SupportedExchange.BITTREX.getInternalId(),
        List.of(PARAMETER_API_KEY, PARAMETER_API_SECRET)
    );

    private final String apiKey;
    private final String apiSecret;

    public BittrexConnector(Map<String, String> parameters) {
        Objects.requireNonNull(this.apiKey = parameters.get(PARAMETER_API_KEY.getId()));
        Objects.requireNonNull(this.apiSecret = parameters.get(PARAMETER_API_SECRET.getId()));
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public DownloadResult getTransactions(String lastTransactionId) {
        final ExchangeSpecification exSpec = new BittrexExchange().getDefaultExchangeSpecification();
        exSpec.setApiKey(apiKey);
        exSpec.setSecretKey(apiSecret);
        final Exchange exchange = ExchangeFactory.INSTANCE.createExchange(exSpec);
        final TradeService tradeService = exchange.getTradeService();

        List<UserTrade> userTrades = download(lastTransactionId, tradeService);

        final String actualLastTransactionId = userTrades.isEmpty()
            ? lastTransactionId
            : userTrades.get(userTrades.size() - 1).getId();

        final ParseResult parseResult = getParseResult(userTrades);

        return new DownloadResult(parseResult, actualLastTransactionId);
    }

    private List<UserTrade> download(String lastTransactionId, TradeService tradeService) {
        final TradeHistoryParams tradeHistoryParams = tradeService.createTradeHistoryParams();
        final List<UserTrade> userTradesBlock;
        try {
            userTradesBlock = tradeService.getTradeHistory(tradeHistoryParams).getUserTrades();
        } catch (Exception e) {
            throw new IllegalStateException("User trade history download failed.", e);
        }

        if (lastTransactionId == null) {
            return userTradesBlock;
        }

        final List<UserTrade> userTradesToAdd;
        final int duplicityTxIndex = findDuplicity(lastTransactionId, userTradesBlock);
        if (duplicityTxIndex > -1) {
            if (duplicityTxIndex < userTradesBlock.size() - 1) {
                userTradesToAdd = userTradesBlock.subList(duplicityTxIndex + 1, userTradesBlock.size());
            } else {
                userTradesToAdd = List.of();
            }
        } else {
            userTradesToAdd = userTradesBlock;
        }

        return userTradesToAdd;
    }

    private ParseResult getParseResult(List<UserTrade> userTrades) {
        final List<ImportedTransactionBean> importedTransactionBeans = new ArrayList<>();
        final List<RowError> errorRows = new ArrayList<>();
        for (UserTrade userTrade : userTrades) {
            try {
                XChangeApiTransactionBean xChangeApiTransactionBean
                    = new XChangeApiTransactionBean(userTrade, SupportedExchange.BITTREX);
                importedTransactionBeans.add(xChangeApiTransactionBean.toImportedTransactionBean());
            } catch (Exception e) {
                log.error("Error converting to ImportedTransactionBean.", e);
                errorRows.add(new RowError(userTrade.toString(), e.getMessage(), RowErrorType.FAILED));
            }
        }

        return new ParseResult(
            importedTransactionBeans,
            new ConversionStatistic(errorRows, 0)
        );
    }

    private int findDuplicity(String transactionId, List<UserTrade> userTradesBlock) {
        for (int i = 0; i < userTradesBlock.size(); i++) {
            final UserTrade userTrade = userTradesBlock.get(i);
            if (userTrade.getId().equals(transactionId)) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        //AutoCloseable
    }
}