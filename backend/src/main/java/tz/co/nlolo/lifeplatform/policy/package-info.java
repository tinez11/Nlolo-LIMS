// distribution::api added for agents-realm object-level scoping ("browse my book of business"):
// PolicyController/PolicyApiImpl resolve a caller's agent hierarchy team via
// DistributionApi.resolveAgentTeam, closing the gap PolicyController's own getPolicy javadoc
// named as deferred ("no agent/agency data model... to resolve 'is this caller's agent identity
// the agentOfRecord' against").
@org.springframework.modulith.ApplicationModule(allowedDependencies = { "underwriting::api", "product::api", "party::api", "document::api", "refdata::api", "distribution::api" })
package tz.co.nlolo.lifeplatform.policy;
