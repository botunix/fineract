/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.fineract.accounting.journalentry.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.apache.fineract.accounting.closure.domain.GLClosure;
import org.apache.fineract.accounting.common.AccountingConstants.AccrualAccountsForLoan;
import org.apache.fineract.accounting.common.AccountingConstants.CashAccountsForLoan;
import org.apache.fineract.accounting.common.AccountingConstants.FinancialActivity;
import org.apache.fineract.accounting.glaccount.domain.GLAccount;
import org.apache.fineract.accounting.journalentry.data.ChargePaymentDTO;
import org.apache.fineract.accounting.journalentry.data.LoanDTO;
import org.apache.fineract.accounting.journalentry.data.LoanTransactionDTO;
import org.apache.fineract.organisation.office.domain.Office;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccrualBasedAccountingProcessorForLoan implements AccountingProcessorForLoan {

    private final AccountingProcessorHelper helper;

    @Override
    public void createJournalEntriesForLoan(final LoanDTO loanDTO) {
        final GLClosure latestGLClosure = this.helper.getLatestClosureByBranch(loanDTO.getOfficeId());
        final Office office = this.helper.getOfficeById(loanDTO.getOfficeId());
        for (final LoanTransactionDTO loanTransactionDTO : loanDTO.getNewLoanTransactions()) {
            final Date transactionDate = loanTransactionDTO.getTransactionDate();
            this.helper.checkForBranchClosures(latestGLClosure, transactionDate);

            /** Handle Disbursements **/
            if (loanTransactionDTO.getTransactionType().isDisbursement()) {
                createJournalEntriesForDisbursements(loanDTO, loanTransactionDTO, office);
            }

            /*** Handle Accruals ***/
            if (loanTransactionDTO.getTransactionType().isAccrual()) {
                createJournalEntriesForAccruals(loanDTO, loanTransactionDTO, office);
            }

            /***
             * Handle repayments, repayments at disbursement and reversal of Repayments and Repayments at disbursement
             ***/
            else if (loanTransactionDTO.getTransactionType().isRepayment()
                    || loanTransactionDTO.getTransactionType().isRepaymentAtDisbursement()
                    || loanTransactionDTO.getTransactionType().isChargePayment()) {
                createJournalEntriesForRepaymentsAndWriteOffs(loanDTO, loanTransactionDTO, office, false,
                        loanTransactionDTO.getTransactionType().isRepaymentAtDisbursement());
            }

            /** Logic for handling recovery payments **/
            else if (loanTransactionDTO.getTransactionType().isRecoveryRepayment()) {
                createJournalEntriesForRecoveryRepayments(loanDTO, loanTransactionDTO, office);
            }

            /** Logic for Refunds of Overpayments **/
            else if (loanTransactionDTO.getTransactionType().isRefund()) {
                createJournalEntriesForRefund(loanDTO, loanTransactionDTO, office);
            }

            /** Logic for Credit Balance Refunds **/
            else if (loanTransactionDTO.getTransactionType().isCreditBalanceRefund()) {
                createJournalEntriesForCreditBalanceRefund(loanDTO, loanTransactionDTO, office);
            }

            /** Handle Write Offs, waivers and their reversals **/
            else if ((loanTransactionDTO.getTransactionType().isWriteOff() || loanTransactionDTO.getTransactionType().isWaiveInterest()
                    || loanTransactionDTO.getTransactionType().isWaiveCharges())) {
                createJournalEntriesForRepaymentsAndWriteOffs(loanDTO, loanTransactionDTO, office, true, false);
            }

            /** Logic for Refunds of Active Loans **/
            else if (loanTransactionDTO.getTransactionType().isRefundForActiveLoans()) {
                createJournalEntriesForRefundForActiveLoan(loanDTO, loanTransactionDTO, office);
            }
        }
    }

    /**
     * Debit loan Portfolio and credit Fund source for Disbursement.
     *
     * @param loanDTO
     * @param loanTransactionDTO
     * @param office
     */
    private void createJournalEntriesForDisbursements(final LoanDTO loanDTO, final LoanTransactionDTO loanTransactionDTO,
            final Office office) {

        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal disbursalAmount = loanTransactionDTO.getAmount();
        final boolean isReversed = loanTransactionDTO.isReversed();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();

        // create journal entries for the disbursement (or disbursement
        // reversal)
        if (loanTransactionDTO.isLoanToLoanTransfer()) {
            this.helper.createAccrualBasedJournalEntriesAndReversalsForLoan(office, currencyCode,
                    AccrualAccountsForLoan.LOAN_PORTFOLIO.getValue(), FinancialActivity.ASSET_TRANSFER.getValue(), loanProductId,
                    paymentTypeId, loanId, transactionId, transactionDate, disbursalAmount, isReversed);
        } else if (loanTransactionDTO.isAccountTransfer()) {
            this.helper.createAccrualBasedJournalEntriesAndReversalsForLoan(office, currencyCode,
                    AccrualAccountsForLoan.LOAN_PORTFOLIO.getValue(), FinancialActivity.LIABILITY_TRANSFER.getValue(), loanProductId,
                    paymentTypeId, loanId, transactionId, transactionDate, disbursalAmount, isReversed);
        } else {
            this.helper.createAccrualBasedJournalEntriesAndReversalsForLoan(office, currencyCode,
                    AccrualAccountsForLoan.LOAN_PORTFOLIO.getValue(), AccrualAccountsForLoan.FUND_SOURCE.getValue(), loanProductId,
                    paymentTypeId, loanId, transactionId, transactionDate, disbursalAmount, isReversed);
        }

    }

    /**
     *
     * Handles repayments using the following posting rules <br/>
     * <br/>
     * <br/>
     *
     * <b>Principal Repayment</b>: Debits "Fund Source" and Credits "Loan Portfolio"<br/>
     *
     * <b>Interest Repayment</b>:Debits "Fund Source" and and Credits "Receivable Interest" <br/>
     *
     * <b>Fee Repayment</b>:Debits "Fund Source" (or "Interest on Loans" in case of repayment at disbursement) and and
     * Credits "Receivable Fees" <br/>
     *
     * <b>Penalty Repayment</b>: Debits "Fund Source" and and Credits "Receivable Penalties" <br/>
     * <br/>
     * Handles write offs using the following posting rules <br/>
     * <br/>
     * <b>Principal Write off</b>: Debits "Losses Written Off" and Credits "Loan Portfolio"<br/>
     *
     * <b>Interest Write off</b>:Debits "Losses Written off" and and Credits "Receivable Interest" <br/>
     *
     * <b>Fee Write off</b>:Debits "Losses Written off" and and Credits "Receivable Fees" <br/>
     *
     * <b>Penalty Write off</b>: Debits "Losses Written off" and and Credits "Receivable Penalties" <br/>
     * <br/>
     * <br/>
     * In case the loan transaction has been reversed, all debits are turned into credits and vice versa
     *
     * @param loanTransactionDTO
     * @param loanDTO
     * @param office
     *
     */
    private void createJournalEntriesForRepaymentsAndWriteOffs(final LoanDTO loanDTO, final LoanTransactionDTO loanTransactionDTO,
            final Office office, final boolean writeOff, final boolean isIncomeFromFee) {
        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal principalAmount = loanTransactionDTO.getPrincipal();
        final BigDecimal interestAmount = loanTransactionDTO.getInterest();
        final BigDecimal feesAmount = loanTransactionDTO.getFees();
        final BigDecimal penaltiesAmount = loanTransactionDTO.getPenalties();
        final BigDecimal overPaymentAmount = loanTransactionDTO.getOverPayment();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();
        final boolean isReversal = loanTransactionDTO.isReversed();

        BigDecimal totalDebitAmount = new BigDecimal(0);

        Map<GLAccount, BigDecimal> accountMap = new HashMap<>();

        // handle principal payment or writeOff (and reversals)
        if (principalAmount != null && !(principalAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(principalAmount);
            GLAccount account = this.helper.getLinkedGLAccountForLoanProduct(loanProductId,
                    AccrualAccountsForLoan.LOAN_PORTFOLIO.getValue(), paymentTypeId);
            accountMap.put(account, principalAmount);
        }

        // handle interest payment of writeOff (and reversals)
        if (interestAmount != null && !(interestAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(interestAmount);
            GLAccount account = this.helper.getLinkedGLAccountForLoanProduct(loanProductId,
                    AccrualAccountsForLoan.INTEREST_RECEIVABLE.getValue(), paymentTypeId);
            if (accountMap.containsKey(account)) {
                BigDecimal amount = accountMap.get(account).add(interestAmount);
                accountMap.put(account, amount);
            } else {
                accountMap.put(account, interestAmount);
            }
        }

        // handle fees payment of writeOff (and reversals)
        if (feesAmount != null && !(feesAmount.compareTo(BigDecimal.ZERO) == 0)) {

            totalDebitAmount = totalDebitAmount.add(feesAmount);

            if (isIncomeFromFee) {
                this.helper.createCreditJournalEntryOrReversalForLoanCharges(office, currencyCode,
                        AccrualAccountsForLoan.INCOME_FROM_FEES.getValue(), loanProductId, loanId, transactionId, transactionDate,
                        feesAmount, isReversal, loanTransactionDTO.getFeePayments());
            } else {
                GLAccount account = this.helper.getLinkedGLAccountForLoanProduct(loanProductId,
                        AccrualAccountsForLoan.FEES_RECEIVABLE.getValue(), paymentTypeId);
                if (accountMap.containsKey(account)) {
                    BigDecimal amount = accountMap.get(account).add(feesAmount);
                    accountMap.put(account, amount);
                } else {
                    accountMap.put(account, feesAmount);
                }
            }
        }

        // handle penalties payment of writeOff (and reversals)
        if (penaltiesAmount != null && !(penaltiesAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(penaltiesAmount);
            if (isIncomeFromFee) {
                GLAccount account = this.helper.getLinkedGLAccountForLoanProduct(loanProductId,
                        AccrualAccountsForLoan.INCOME_FROM_PENALTIES.getValue(), paymentTypeId);
                if (accountMap.containsKey(account)) {
                    BigDecimal amount = accountMap.get(account).add(penaltiesAmount);
                    accountMap.put(account, amount);
                } else {
                    accountMap.put(account, penaltiesAmount);
                }
            } else {
                GLAccount account = this.helper.getLinkedGLAccountForLoanProduct(loanProductId,
                        AccrualAccountsForLoan.PENALTIES_RECEIVABLE.getValue(), paymentTypeId);
                if (accountMap.containsKey(account)) {
                    BigDecimal amount = accountMap.get(account).add(penaltiesAmount);
                    accountMap.put(account, amount);
                } else {
                    accountMap.put(account, penaltiesAmount);
                }
            }
        }

        if (overPaymentAmount != null && !(overPaymentAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(overPaymentAmount);
            GLAccount account = this.helper.getLinkedGLAccountForLoanProduct(loanProductId, AccrualAccountsForLoan.OVERPAYMENT.getValue(),
                    paymentTypeId);
            if (accountMap.containsKey(account)) {
                BigDecimal amount = accountMap.get(account).add(overPaymentAmount);
                accountMap.put(account, amount);
            } else {
                accountMap.put(account, overPaymentAmount);
            }
        }

        for (Map.Entry<GLAccount, BigDecimal> entry : accountMap.entrySet()) {
            this.helper.createCreditJournalEntryOrReversalForLoan(office, currencyCode, loanId, transactionId, transactionDate,
                    entry.getValue(), isReversal, entry.getKey());
        }

        /**
         * Single DEBIT transaction for write-offs or Repayments (and their reversals)
         ***/
        if (!(totalDebitAmount.compareTo(BigDecimal.ZERO) == 0)) {
            if (writeOff) {
                this.helper.createDebitJournalEntryOrReversalForLoan(office, currencyCode,
                        AccrualAccountsForLoan.LOSSES_WRITTEN_OFF.getValue(), loanProductId, paymentTypeId, loanId, transactionId,
                        transactionDate, totalDebitAmount, isReversal);
            } else {
                if (loanTransactionDTO.isLoanToLoanTransfer()) {
                    this.helper.createDebitJournalEntryOrReversalForLoan(office, currencyCode, FinancialActivity.ASSET_TRANSFER.getValue(),
                            loanProductId, paymentTypeId, loanId, transactionId, transactionDate, totalDebitAmount, isReversal);
                } else if (loanTransactionDTO.isAccountTransfer()) {
                    this.helper.createDebitJournalEntryOrReversalForLoan(office, currencyCode,
                            FinancialActivity.LIABILITY_TRANSFER.getValue(), loanProductId, paymentTypeId, loanId, transactionId,
                            transactionDate, totalDebitAmount, isReversal);
                } else {
                    this.helper.createDebitJournalEntryOrReversalForLoan(office, currencyCode,
                            AccrualAccountsForLoan.FUND_SOURCE.getValue(), loanProductId, paymentTypeId, loanId, transactionId,
                            transactionDate, totalDebitAmount, isReversal);
                }
            }
        }
    }

    /**
     * Create a single Debit to fund source and a single credit to "Income from Recovery"
     *
     * In case the loan transaction is a reversal, all debits are turned into credits and vice versa
     */
    private void createJournalEntriesForRecoveryRepayments(final LoanDTO loanDTO, final LoanTransactionDTO loanTransactionDTO,
            final Office office) {
        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal amount = loanTransactionDTO.getAmount();
        final boolean isReversal = loanTransactionDTO.isReversed();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();

        this.helper.createAccrualBasedJournalEntriesAndReversalsForLoan(office, currencyCode, AccrualAccountsForLoan.FUND_SOURCE.getValue(),
                AccrualAccountsForLoan.INCOME_FROM_RECOVERY.getValue(), loanProductId, paymentTypeId, loanId, transactionId,
                transactionDate, amount, isReversal);

    }

    /**
     * Recognize the receivable interest <br/>
     * Debit "Interest Receivable" and Credit "Income from Interest"
     *
     * <b>Fees:</b> Debit <i>Fees Receivable</i> and credit <i>Income from Fees</i> <br/>
     *
     * <b>Penalties:</b> Debit <i>Penalties Receivable</i> and credit <i>Income from Penalties</i>
     *
     * Also handles reversals for both fees and payment applications
     *
     * @param loanDTO
     * @param loanTransactionDTO
     * @param office
     */
    private void createJournalEntriesForAccruals(final LoanDTO loanDTO, final LoanTransactionDTO loanTransactionDTO, final Office office) {

        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal interestAmount = loanTransactionDTO.getInterest();
        final BigDecimal feesAmount = loanTransactionDTO.getFees();
        final BigDecimal penaltiesAmount = loanTransactionDTO.getPenalties();
        final boolean isReversed = loanTransactionDTO.isReversed();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();

        // create journal entries for recognizing interest (or reversal)
        if (interestAmount != null && !(interestAmount.compareTo(BigDecimal.ZERO) == 0)) {
            this.helper.createAccrualBasedJournalEntriesAndReversalsForLoan(office, currencyCode,
                    AccrualAccountsForLoan.INTEREST_RECEIVABLE.getValue(), AccrualAccountsForLoan.INTEREST_ON_LOANS.getValue(),
                    loanProductId, paymentTypeId, loanId, transactionId, transactionDate, interestAmount, isReversed);
        }
        // create journal entries for the fees application (or reversal)
        if (feesAmount != null && !(feesAmount.compareTo(BigDecimal.ZERO) == 0)) {
            this.helper.createAccrualBasedJournalEntriesAndReversalsForLoanCharges(office, currencyCode,
                    AccrualAccountsForLoan.FEES_RECEIVABLE.getValue(), AccrualAccountsForLoan.INCOME_FROM_FEES.getValue(), loanProductId,
                    loanId, transactionId, transactionDate, feesAmount, isReversed, loanTransactionDTO.getFeePayments());
        }
        // create journal entries for the penalties application (or reversal)
        if (penaltiesAmount != null && !(penaltiesAmount.compareTo(BigDecimal.ZERO) == 0)) {

            this.helper.createAccrualBasedJournalEntriesAndReversalsForLoanCharges(office, currencyCode,
                    AccrualAccountsForLoan.PENALTIES_RECEIVABLE.getValue(), AccrualAccountsForLoan.INCOME_FROM_PENALTIES.getValue(),
                    loanProductId, loanId, transactionId, transactionDate, penaltiesAmount, isReversed,
                    loanTransactionDTO.getPenaltyPayments());
        }
    }

    private void createJournalEntriesForRefund(final LoanDTO loanDTO, final LoanTransactionDTO loanTransactionDTO, final Office office) {
        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal refundAmount = loanTransactionDTO.getAmount();
        final boolean isReversal = loanTransactionDTO.isReversed();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();

        if (loanTransactionDTO.isAccountTransfer()) {
            this.helper.createCashBasedJournalEntriesAndReversalsForLoan(office, currencyCode, CashAccountsForLoan.OVERPAYMENT.getValue(),
                    FinancialActivity.LIABILITY_TRANSFER.getValue(), loanProductId, paymentTypeId, loanId, transactionId, transactionDate,
                    refundAmount, isReversal);
        } else {
            this.helper.createCashBasedJournalEntriesAndReversalsForLoan(office, currencyCode, CashAccountsForLoan.OVERPAYMENT.getValue(),
                    CashAccountsForLoan.FUND_SOURCE.getValue(), loanProductId, paymentTypeId, loanId, transactionId, transactionDate,
                    refundAmount, isReversal);
        }
    }

    private void createJournalEntriesForCreditBalanceRefund(final LoanDTO loanDTO, final LoanTransactionDTO loanTransactionDTO,
            final Office office) {
        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal refundAmount = loanTransactionDTO.getAmount();
        final boolean isReversal = loanTransactionDTO.isReversed();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();

        this.helper.createAccrualBasedJournalEntriesAndReversalsForLoan(office, currencyCode,
                AccrualAccountsForLoan.LOAN_PORTFOLIO.getValue(), AccrualAccountsForLoan.OVERPAYMENT.getValue(), loanProductId,
                paymentTypeId, loanId, transactionId, transactionDate, refundAmount, isReversal);
    }

    private void createJournalEntriesForRefundForActiveLoan(LoanDTO loanDTO, LoanTransactionDTO loanTransactionDTO, Office office) {
        // TODO Auto-generated method stub
        // loan properties
        final Long loanProductId = loanDTO.getLoanProductId();
        final Long loanId = loanDTO.getLoanId();
        final String currencyCode = loanDTO.getCurrencyCode();

        // transaction properties
        final String transactionId = loanTransactionDTO.getTransactionId();
        final Date transactionDate = loanTransactionDTO.getTransactionDate();
        final BigDecimal principalAmount = loanTransactionDTO.getPrincipal();
        final BigDecimal interestAmount = loanTransactionDTO.getInterest();
        final BigDecimal feesAmount = loanTransactionDTO.getFees();
        final BigDecimal penaltiesAmount = loanTransactionDTO.getPenalties();
        final BigDecimal overPaymentAmount = loanTransactionDTO.getOverPayment();
        final boolean isReversal = loanTransactionDTO.isReversed();
        final Long paymentTypeId = loanTransactionDTO.getPaymentTypeId();

        BigDecimal totalDebitAmount = new BigDecimal(0);

        if (principalAmount != null && !(principalAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(principalAmount);
            this.helper.createCreditJournalEntryOrReversalForLoan(office, currencyCode, CashAccountsForLoan.LOAN_PORTFOLIO, loanProductId,
                    paymentTypeId, loanId, transactionId, transactionDate, principalAmount, !isReversal);
        }

        if (interestAmount != null && !(interestAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(interestAmount);
            this.helper.createCreditJournalEntryOrReversalForLoan(office, currencyCode, CashAccountsForLoan.INTEREST_ON_LOANS,
                    loanProductId, paymentTypeId, loanId, transactionId, transactionDate, interestAmount, !isReversal);
        }

        if (feesAmount != null && !(feesAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(feesAmount);

            List<ChargePaymentDTO> chargePaymentDTOs = new ArrayList<>();

            for (ChargePaymentDTO chargePaymentDTO : loanTransactionDTO.getFeePayments()) {
                chargePaymentDTOs.add(new ChargePaymentDTO(chargePaymentDTO.getChargeId(),
                        chargePaymentDTO.getAmount().floatValue() < 0 ? chargePaymentDTO.getAmount().multiply(new BigDecimal(-1))
                                : chargePaymentDTO.getAmount(),
                        chargePaymentDTO.getLoanChargeId()));
            }
            this.helper.createCreditJournalEntryOrReversalForLoanCharges(office, currencyCode,
                    CashAccountsForLoan.INCOME_FROM_FEES.getValue(), loanProductId, loanId, transactionId, transactionDate, feesAmount,
                    !isReversal, chargePaymentDTOs);
        }

        if (penaltiesAmount != null && !(penaltiesAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(penaltiesAmount);
            List<ChargePaymentDTO> chargePaymentDTOs = new ArrayList<>();

            for (ChargePaymentDTO chargePaymentDTO : loanTransactionDTO.getPenaltyPayments()) {
                chargePaymentDTOs.add(new ChargePaymentDTO(chargePaymentDTO.getChargeId(),
                        chargePaymentDTO.getAmount().floatValue() < 0 ? chargePaymentDTO.getAmount().multiply(new BigDecimal(-1))
                                : chargePaymentDTO.getAmount(),
                        chargePaymentDTO.getLoanChargeId()));
            }

            this.helper.createCreditJournalEntryOrReversalForLoanCharges(office, currencyCode,
                    CashAccountsForLoan.INCOME_FROM_PENALTIES.getValue(), loanProductId, loanId, transactionId, transactionDate,
                    penaltiesAmount, !isReversal, chargePaymentDTOs);
        }

        if (overPaymentAmount != null && !(overPaymentAmount.compareTo(BigDecimal.ZERO) == 0)) {
            totalDebitAmount = totalDebitAmount.add(overPaymentAmount);
            this.helper.createCreditJournalEntryOrReversalForLoan(office, currencyCode, CashAccountsForLoan.OVERPAYMENT, loanProductId,
                    paymentTypeId, loanId, transactionId, transactionDate, overPaymentAmount, !isReversal);
        }

        /*** create a single debit entry (or reversal) for the entire amount **/
        this.helper.createDebitJournalEntryOrReversalForLoan(office, currencyCode, CashAccountsForLoan.FUND_SOURCE.getValue(),
                loanProductId, paymentTypeId, loanId, transactionId, transactionDate, totalDebitAmount, !isReversal);

    }
}
