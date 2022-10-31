package io.everytrade.server.plugin.impl.everytrade.parser.exchange.bean;

import com.univocity.parsers.common.DataValidationException;
import io.everytrade.server.model.Currency;
import io.everytrade.server.model.TransactionType;
import io.everytrade.server.plugin.api.parser.FeeRebateImportedTransactionBean;
import io.everytrade.server.plugin.api.parser.ImportedTransactionBean;
import io.everytrade.server.plugin.api.parser.TransactionCluster;
import io.everytrade.server.plugin.impl.everytrade.parser.exchange.ExchangeBean;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.FieldDefaults;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static io.everytrade.server.model.TransactionType.FEE;
import static io.everytrade.server.model.TransactionType.REBATE;
import static io.everytrade.server.model.TransactionType.REWARD;
import static io.everytrade.server.plugin.impl.everytrade.parser.ParserUtils.DECIMAL_DIGITS;
import static io.everytrade.server.plugin.impl.everytrade.parser.ParserUtils.nullOrZero;
import static java.math.RoundingMode.HALF_UP;
import static java.util.Collections.emptyList;
import static lombok.AccessLevel.PROTECTED;

@Setter
@Getter
@FieldDefaults(level = PROTECTED)
public abstract class BaseBeanV1 extends ExchangeBean {
    String uid;
    Instant executed;
    Currency base;
    Currency quote;
    TransactionType transactionType;
    BigDecimal volume; // sometimes called baseAmount;
    BigDecimal unitPrice; // usually count by base and quote amount

    BigDecimal feeAmount;

    String fee;
    Currency feeCurrency;
    BigDecimal rebateAmount;
    String rebate;
    Currency rebateCurrency;

    BigDecimal quoteAmount;
    String address;
    String label;
    String note;

    boolean ignoredFee;
    boolean failedFee;
    String ignoredOrFailedFeeMessage;

    protected abstract TransactionType findTransactionType();

    protected abstract void mapData();

    @Override
    public TransactionCluster toTransactionCluster() {
        mapData();
        TransactionCluster cluster =
            switch (transactionType) {
                case REWARD -> createRewardTransactionCluster();
                case BUY, SELL -> createBuySellTransactionCluster();
                case DEPOSIT, WITHDRAWAL -> createDepositOrWithdrawalTxCluster();
                case STAKE, UNSTAKE, STAKING_REWARD, EARNING, FORK, AIRDROP -> createOtherTransactionCluster();
                case FEE, REBATE -> createUnrelatedFeeOrRebate();
                default -> throw new DataValidationException(String.format("Unsupported transaction type %s.", transactionType.name()));
            };
        if (ignoredFee) {
            cluster.setIgnoredFee(1, ignoredOrFailedFeeMessage);
        }
        if (failedFee) {
            cluster.setFailedFee(1, ignoredOrFailedFeeMessage);
        }
        return cluster;
    }

    private TransactionCluster createDepositOrWithdrawalTxCluster() {
        var tx = ImportedTransactionBean.createDepositWithdrawal(
            uid,
            executed,
            base, //base
            quote,  //quote
            transactionType,
            volume,
            base.isFiat() ? null : address
        );
        return new TransactionCluster(tx, getRelatedTransactions());
    }

    private TransactionCluster createRewardTransactionCluster() {
        return new TransactionCluster(
            new ImportedTransactionBean(
                uid,
                executed,
                base,
                base,
                REWARD,
                volume,
                null
            ),
            emptyList()
        );
    }

    private void validateBuySellTx() {
        validateCurrencyPair(base, quote);
        validateCurrencyPair(base, quote, transactionType);
        if (base.isFiat()) {
            throw new DataValidationException(String.format("Base currency is not crypto: %s", base.code()));
        }
    }

    private TransactionCluster createBuySellTransactionCluster() {
        validateBuySellTx();
        TransactionCluster cluster;
        cluster = new TransactionCluster(
            new ImportedTransactionBean(
                uid,
                executed,
                base,
                quote,
                transactionType,
                volume,
                unitPrice
            ),
            getRelatedTransactions()
        );
        return cluster;
    }

    public BigDecimal countUnitPrice() {
        return evalUnitPrice(quoteAmount, volume);
    }

    private List<ImportedTransactionBean> getRelatedTransactions() {
        List<ImportedTransactionBean> related = new java.util.ArrayList<>(emptyList());
        try {
            if (!nullOrZero(feeAmount)) {
                feeCurrency = Currency.fromCode(fee);
                var feeTx = new FeeRebateImportedTransactionBean(
                    (uid != null) ? uid + FEE_UID_PART : null,
                    executed,
                    feeCurrency,
                    feeCurrency,
                    FEE,
                    feeAmount.setScale(DECIMAL_DIGITS, HALF_UP),
                    feeCurrency
                );
                related.add(feeTx);
            }
            if (!nullOrZero(rebateAmount)) {
                rebateCurrency = Currency.fromCode(rebate);
                var rebateTx = new FeeRebateImportedTransactionBean(
                    (uid != null) ? uid + REBATE_UID_PART : null,
                    executed,
                    rebateCurrency,
                    rebateCurrency,
                    REBATE,
                    rebateAmount.setScale(DECIMAL_DIGITS, HALF_UP),
                    rebateCurrency
                );
                related.add(rebateTx);
            }
        } catch (IllegalArgumentException e) {
            ignoredFee = true;
            ignoredOrFailedFeeMessage += e.getMessage();
        } catch (Exception e) {
            failedFee = true;
            ignoredOrFailedFeeMessage += e.getMessage();
        }
        return related;
    }

    private TransactionCluster createUnrelatedFeeOrRebate() {
        return new TransactionCluster(new FeeRebateImportedTransactionBean(
            uid,
            executed,
            base,
            base,
            transactionType,
            volume,
            base
        ), getRelatedTransactions());
    }

    private TransactionCluster createOtherTransactionCluster() {
        var tx = new ImportedTransactionBean(
            uid,
            executed,
            base,
            base,
            transactionType,
            volume,
            unitPrice,
            note,
            address
        );
        return new TransactionCluster(tx, getRelatedTransactions());
    }

}
