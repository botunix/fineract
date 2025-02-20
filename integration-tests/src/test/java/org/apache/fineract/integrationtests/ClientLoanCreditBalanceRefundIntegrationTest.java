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
package org.apache.fineract.integrationtests;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.restassured.builder.RequestSpecBuilder;
import io.restassured.builder.ResponseSpecBuilder;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import io.restassured.specification.ResponseSpecification;
import java.util.ArrayList;
import java.util.HashMap;
import org.apache.fineract.integrationtests.common.ClientHelper;
import org.apache.fineract.integrationtests.common.CommonConstants;
import org.apache.fineract.integrationtests.common.Utils;
import org.apache.fineract.integrationtests.common.accounting.Account;
import org.apache.fineract.integrationtests.common.accounting.AccountHelper;
import org.apache.fineract.integrationtests.common.accounting.JournalEntry;
import org.apache.fineract.integrationtests.common.accounting.JournalEntryHelper;
import org.apache.fineract.integrationtests.common.loans.LoanApplicationTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanProductTestBuilder;
import org.apache.fineract.integrationtests.common.loans.LoanStatusChecker;
import org.apache.fineract.integrationtests.common.loans.LoanTransactionHelper;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings({ "rawtypes", "unchecked" })
public class ClientLoanCreditBalanceRefundIntegrationTest {

    private static final Logger LOG = LoggerFactory.getLogger(ClientLoanCreditBalanceRefundIntegrationTest.class);

    private ResponseSpecification responseSpec;
    private RequestSpecification requestSpec;
    private LoanTransactionHelper loanTransactionHelper;
    private LoanTransactionHelper loanTransactionHelperValidationError;
    private JournalEntryHelper journalEntryHelper;
    private AccountHelper accountHelper;
    private Integer disbursedLoanID;
    private static final String ACCRUAL_PERIODIC = "3";
    private Account assetAccount;
    private Account incomeAccount;
    private Account expenseAccount;
    private Account overpaymentAccount;

    @BeforeEach
    public void setup() {
        Utils.initializeRESTAssured();
        this.requestSpec = new RequestSpecBuilder().setContentType(ContentType.JSON).build();
        this.requestSpec.header("Authorization", "Basic " + Utils.loginIntoServerAndGetBase64EncodedAuthenticationKey());
        this.responseSpec = new ResponseSpecBuilder().expectStatusCode(200).build();
        this.loanTransactionHelper = new LoanTransactionHelper(this.requestSpec, this.responseSpec);
        this.loanTransactionHelperValidationError = new LoanTransactionHelper(this.requestSpec, new ResponseSpecBuilder().build());
        this.accountHelper = new AccountHelper(this.requestSpec, this.responseSpec);
        this.assetAccount = this.accountHelper.createAssetAccount();
        this.incomeAccount = this.accountHelper.createIncomeAccount();
        this.expenseAccount = this.accountHelper.createExpenseAccount();
        this.overpaymentAccount = this.accountHelper.createLiabilityAccount();
        this.journalEntryHelper = new JournalEntryHelper(this.requestSpec, this.responseSpec);

        final String principal = "12000.00";
        final String submitApproveDisburseDate = "01 January 2022";
        this.disbursedLoanID = fromStartToDisburseLoan(submitApproveDisburseDate, principal, ACCRUAL_PERIODIC, assetAccount, incomeAccount,
                expenseAccount, overpaymentAccount);

    }

    private Integer createLoanProduct(final String principal, final boolean multiDisburseLoan, final String accountingRule,
            final Account... accounts) {
        LOG.info("------------------------------CREATING NEW LOAN PRODUCT ---------------------------------------");
        LoanProductTestBuilder builder = new LoanProductTestBuilder() //
                .withPrincipal(principal) //
                .withNumberOfRepayments("4") //
                .withRepaymentAfterEvery("1") //
                .withRepaymentTypeAsMonth() //
                .withinterestRatePerPeriod("1") //
                .withInterestRateFrequencyTypeAsMonths() //
                .withAmortizationTypeAsEqualInstallments() //
                .withInterestTypeAsDecliningBalance() //
                .withAccounting(accountingRule, accounts) //
                .withTranches(multiDisburseLoan);
        if (multiDisburseLoan) {
            builder = builder.withInterestCalculationPeriodTypeAsRepaymentPeriod(true);
            builder = builder.withMaxTrancheCount("30");
        }
        final String loanProductJSON = builder.build(null);
        return this.loanTransactionHelper.getLoanProductId(loanProductJSON);
    }

    private Integer applyForLoanApplication(final Integer clientID, final Integer loanProductID, String principal, String submitDate) {
        LOG.info("--------------------------------APPLYING FOR LOAN APPLICATION--------------------------------");
        final String loanApplicationJSON = new LoanApplicationTestBuilder() //
                .withPrincipal(principal) //
                .withLoanTermFrequency("4") //
                .withLoanTermFrequencyAsMonths() //
                .withNumberOfRepayments("4") //
                .withRepaymentEveryAfter("1") //
                .withRepaymentFrequencyTypeAsMonths() //
                .withInterestRatePerPeriod("2") //
                .withAmortizationTypeAsEqualInstallments() //
                .withInterestTypeAsDecliningBalance() //
                .withInterestCalculationPeriodTypeSameAsRepaymentPeriod() //
                .withExpectedDisbursementDate(submitDate) //
                .withSubmittedOnDate(submitDate) //
                .build(clientID.toString(), loanProductID.toString(), null);
        return this.loanTransactionHelper.getLoanId(loanApplicationJSON);
    }

    private Integer fromStartToDisburseLoan(String submitApproveDisburseDate, String principal, final String accountingRule,
            final Account... accounts) {

        final Integer clientID = ClientHelper.createClient(this.requestSpec, this.responseSpec);
        ClientHelper.verifyClientCreatedOnServer(this.requestSpec, this.responseSpec, clientID);

        boolean allowMultipleDisbursals = false;
        final Integer loanProductID = createLoanProduct(principal, allowMultipleDisbursals, accountingRule, accounts);
        Assertions.assertNotNull(loanProductID);

        final Integer loanID = applyForLoanApplication(clientID, loanProductID, principal, submitApproveDisburseDate);
        Assertions.assertNotNull(loanID);
        HashMap loanStatusHashMap = LoanStatusChecker.getStatusOfLoan(this.requestSpec, this.responseSpec, loanID);
        LoanStatusChecker.verifyLoanIsPending(loanStatusHashMap);

        LOG.info("-----------------------------------APPROVE LOAN-----------------------------------------");
        loanStatusHashMap = this.loanTransactionHelper.approveLoan(submitApproveDisburseDate, loanID);
        LoanStatusChecker.verifyLoanIsApproved(loanStatusHashMap);
        LoanStatusChecker.verifyLoanIsWaitingForDisbursal(loanStatusHashMap);

        LOG.info("-------------------------------DISBURSE LOAN -------------------------------------------"); //
        // String loanDetails = this.loanTransactionHelper.getLoanDetails(this.requestSpec, this.responseSpec, loanID);
        loanStatusHashMap = this.loanTransactionHelper.disburseLoan(submitApproveDisburseDate, loanID, principal);
        LoanStatusChecker.verifyLoanIsActive(loanStatusHashMap);
        return loanID;
    }

    private HashMap makeRepayment(final String repaymentDate, final Float repayment) {
        LOG.info("-------------Make repayment -----------");
        this.loanTransactionHelper.makeRepayment(repaymentDate, repayment, disbursedLoanID);
        HashMap loanStatusHashMap = (HashMap) this.loanTransactionHelper.getLoanDetail(this.requestSpec, this.responseSpec, disbursedLoanID,
                "status");
        return loanStatusHashMap;
    }

    @Test
    public void creditBalanceRefundCanOnlyBeAppliedWhereLoanStatusIsOverpaidTest() {
        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 2000.00f); // not full payment
        LoanStatusChecker.verifyLoanIsActive(loanStatusHashMap);

        final String creditBalanceRefundDate = "09 January 2022";
        final Float refund = 1000.00f;
        final String externalId = null;
        ArrayList<HashMap> cbrErrors = (ArrayList<HashMap>) loanTransactionHelperValidationError
                .creditBalanceRefund(creditBalanceRefundDate, refund, externalId, disbursedLoanID, CommonConstants.RESPONSE_ERROR);

        assertEquals("error.msg.loan.credit.balance.refund.account.is.not.overpaid",
                cbrErrors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE));

        // ArrayList<HashMap> loanSchedule = this.loanTransactionHelper.getLoanRepaymentSchedule(this.requestSpec,
        // this.responseSpec, loanID);
        // final int loanScheduleLineCount = loanSchedule.size();

    }

    @Test
    public void cantRefundMoreThanOverpaidTest() {
        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 20000.00f); // overpayment
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

        final String creditBalanceRefundDate = "09 January 2022";
        Float refund = 10000.00f;
        final String externalId = null;
        ArrayList<HashMap> cbrErrors = (ArrayList<HashMap>) loanTransactionHelperValidationError
                .creditBalanceRefund(creditBalanceRefundDate, refund, externalId, disbursedLoanID, CommonConstants.RESPONSE_ERROR);

        assertEquals("error.msg.transactionAmount.invalid.must.be.>zero.and<=overpaidamount",
                cbrErrors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE));

        refund = (float) -1.00;
        cbrErrors = (ArrayList<HashMap>) loanTransactionHelperValidationError.creditBalanceRefund(creditBalanceRefundDate, refund,
                externalId, disbursedLoanID, CommonConstants.RESPONSE_ERROR);
        assertEquals("validation.msg.loan.transaction.transactionAmount.not.greater.than.zero",
                cbrErrors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE));

    }

    @Test
    public void fullRefundChangesStatusToClosedObligationMetTest() {
        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 20000.00f); // overpayment
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

        final Float totalOverpaid = (Float) this.loanTransactionHelper.getLoanDetail(this.requestSpec, this.responseSpec, disbursedLoanID,
                "totalOverpaid");

        final String creditBalanceRefundDate = "09 January 2022";
        final String externalId = null;
        loanTransactionHelper.creditBalanceRefund(creditBalanceRefundDate, totalOverpaid, externalId, disbursedLoanID, null);
        loanStatusHashMap = (HashMap) this.loanTransactionHelper.getLoanDetail(this.requestSpec, this.responseSpec, disbursedLoanID,
                "status");
        LoanStatusChecker.verifyLoanAccountIsClosed(loanStatusHashMap);

        final Float floatZero = 0.0f;
        Float totalOverpaidAtEnd = (Float) this.loanTransactionHelper.getLoanDetail(this.requestSpec, this.responseSpec, disbursedLoanID,
                "totalOverpaid");
        if (totalOverpaidAtEnd == null) {
            totalOverpaidAtEnd = floatZero;
        }
        assertEquals(totalOverpaidAtEnd, floatZero);

    }

    @Test
    public void partialRefundKeepsOverpaidStatusTest() {
        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 20000.00f); // overpayment
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

        final Float refund = 5000.00f; // partial refund

        final String creditBalanceRefundDate = "09 January 2022";
        final String externalId = null;
        loanTransactionHelper.creditBalanceRefund(creditBalanceRefundDate, refund, externalId, disbursedLoanID, null);
        loanStatusHashMap = (HashMap) this.loanTransactionHelper.getLoanDetail(this.requestSpec, this.responseSpec, disbursedLoanID,
                "status");
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

    }

    @Test
    public void newCreditBalanceRefundSavesExternalIdTest() {

        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 20000.00f); // overpayment
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

        final Float refund = 1000.00f; // partial refund
        final String creditBalanceRefundDate = "09 January 2022";
        final String externalId = "cbrextID" + disbursedLoanID.toString();
        Integer resourceId = (Integer) loanTransactionHelper.creditBalanceRefund(creditBalanceRefundDate, refund, externalId,
                disbursedLoanID, "resourceId");
        Assertions.assertNotNull(resourceId);

        HashMap creditBalanceRefundMap = this.loanTransactionHelper.getLoanTransactionDetails(disbursedLoanID, resourceId);
        Assertions.assertNotNull(creditBalanceRefundMap.get("externalId"));
        Assertions.assertEquals(creditBalanceRefundMap.get("externalId"), externalId, "Incorrect External Id Saved");

    }

    @Test
    public void newCreditBalanceRefundFindsDuplicateExternalIdTest() {

        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 20000.00f); // overpayment
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

        final Float refund = 1000.00f; // partial refund
        final String creditBalanceRefundDate = "09 January 2022";
        final String externalId = "cbrextID" + disbursedLoanID.toString();
        final Integer resourceId = (Integer) loanTransactionHelper.creditBalanceRefund(creditBalanceRefundDate, refund, externalId,
                disbursedLoanID, "resourceId");
        Assertions.assertNotNull(resourceId);

        final Float refund2 = 10.00f; // partial refund
        final String creditBalanceRefundDate2 = "10 January 2022";
        ArrayList<HashMap> cbrErrors = (ArrayList<HashMap>) loanTransactionHelperValidationError
                .creditBalanceRefund(creditBalanceRefundDate2, refund2, externalId, disbursedLoanID, CommonConstants.RESPONSE_ERROR);
        assertEquals("error.msg.loan.creditBalanceRefund.duplicate.externalId",
                cbrErrors.get(0).get(CommonConstants.RESPONSE_ERROR_MESSAGE_CODE));

    }

    @Test
    public void newCreditBalanceRefundCreatesCorrectJournalEntriesForPeriodicAccrualsTest() {

        HashMap loanStatusHashMap = makeRepayment("06 January 2022", 20000.00f); // overpayment
        LoanStatusChecker.verifyLoanAccountIsOverPaid(loanStatusHashMap);

        final Float refund = 1000.00f; // partial refund
        final String creditBalanceRefundDate = "09 January 2022";
        final String externalId = null;
        final Integer resourceId = (Integer) loanTransactionHelper.creditBalanceRefund(creditBalanceRefundDate, refund, externalId,
                disbursedLoanID, "resourceId");
        Assertions.assertNotNull(resourceId);

        this.journalEntryHelper.checkJournalEntryForAssetAccount(assetAccount, creditBalanceRefundDate,
                new JournalEntry(refund, JournalEntry.TransactionType.DEBIT));
        this.journalEntryHelper.checkJournalEntryForLiabilityAccount(overpaymentAccount, creditBalanceRefundDate,
                new JournalEntry(refund, JournalEntry.TransactionType.CREDIT));

    }

}
