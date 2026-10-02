package tz.co.nlolo.lifeplatform.accumulation.infrastructure;

import tz.co.nlolo.lifeplatform.accumulation.api.*;

import java.math.BigDecimal;
import java.util.List;

/** A fixed-term deposit, its terms oldest first, and what happens at the end of the running one. */
public record DepositResponse(String policyNumber, List<Period> periods, Instruction instruction, MoneyResponse interestSoFar,
                              String defaultPayeeRef, boolean awaitingPayee, List<Integer> termsOffered) {

    public record Period(String periodId, int seq, MoneyResponse principal, int termMonths, BigDecimal ratePercent,
                         String rateVersionId, String startDate, String maturityDate, String status,
                         MoneyResponse interestPosted, String closedOn) {
        static Period from(DepositPeriodView p, String currency) {
            // NUMERIC(7,4) reads back as 3.0000; the console shows "3% for the term", so trim the
            // scale -- via toPlainString, so that 10 does not become 1E+1.
            return new Period(p.periodId().toString(), p.seq(), MoneyResponse.of(p.principal(), currency), p.termMonths(),
                new BigDecimal(p.ratePercent().stripTrailingZeros().toPlainString()), p.rateVersionId().toString(),
                p.startDate().toString(), p.maturityDate().toString(), p.status().name(),
                p.interestPosted() != null ? MoneyResponse.of(p.interestPosted(), currency) : null,
                p.closedOn() != null ? p.closedOn().toString() : null);
        }
    }

    public record Instruction(String instructionId, String periodId, String action, Integer termMonths, String payeeRef,
                              String recordedBy, String recordedAt) {
        static Instruction from(MaturityInstructionView i) {
            return new Instruction(i.instructionId().toString(), i.periodId().toString(), i.action().name(), i.termMonths(),
                i.payeeRef(), i.recordedBy(), i.recordedAt().toString());
        }
    }

    public record Awaiting(String policyNumber, MoneyResponse balance, String maturedOn) {
        static Awaiting from(AwaitingPayeeView a) {
            return new Awaiting(a.policyNumber(), MoneyResponse.of(a.balance(), a.currency()), a.maturedOn().toString());
        }
    }

    static DepositResponse from(DepositView d) {
        return new DepositResponse(d.policyNumber(), d.periods().stream().map(p -> Period.from(p, d.currency())).toList(),
            d.instruction() != null ? Instruction.from(d.instruction()) : null, MoneyResponse.of(d.interestSoFar(), d.currency()),
            d.defaultPayeeRef(), d.awaitingPayee(), d.termsOffered());
    }
}
