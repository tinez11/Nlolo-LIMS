package tz.co.nlolo.lifeplatform.annuity.api;

/**
 * Where an annuity contract is (product step 5).
 *
 * <ul>
 *   <li>ACCUMULATING -- a deferred annuity (D2) still saving in its account; no form, price or lock until it vests.</li>
 *   <li>AWAITING_PAYMENT -- issued; the single premium has not arrived, so nothing is locked.</li>
 *   <li>IN_PAYMENT -- locked and paying the annuitant.</li>
 *   <li>SURVIVOR -- a joint annuity after the first death, paying the survivor percentage.</li>
 *   <li>GUARANTEE -- the last life died inside the guarantee; paying the beneficiaries until it ends.</li>
 *   <li>ENDED -- nothing further is owed.</li>
 *   <li>CANCELLED -- cancelled in free-look.</li>
 *   <li>LOCK_FAILED -- the premium arrived but the purchase could not be priced; nothing pays.</li>
 * </ul>
 */
public enum ContractStatus { ACCUMULATING, AWAITING_PAYMENT, IN_PAYMENT, SURVIVOR, GUARANTEE, ENDED, CANCELLED, LOCK_FAILED }
