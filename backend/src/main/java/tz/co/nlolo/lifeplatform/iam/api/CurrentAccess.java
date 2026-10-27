package tz.co.nlolo.lifeplatform.iam.api;

/**
 * Whether the signed-in staff member holds a permission, for the few decisions an annotation cannot make -- whether a
 * decision against the recommendation is allowed depends on the case's own recommendation, read inside the request.
 * False for anyone who is not staff.
 */
public interface CurrentAccess {

    boolean can(Permission permission);
}
