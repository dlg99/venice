package com.linkedin.venice.authorization;

import java.security.cert.X509Certificate;


/**
 * An interface to provide functionality to manage ACL's for a {@link Resource} and provide access permission {@link Permission}
 * to principals {@link Principal} when performing {@link Method}. The underlying implementation can
 * choose any mechanism for storing and scanning of the ACL's. No guarantees are made about {@code canAccess} api
 * when duplicate or conflicting AceEntries are present.
 */
public interface AuthorizerService {

  /**
   * Check if the principal has the permission to perform the method on the resource. Implementation should define how to handle
   * duplicate/conflicting ACE entries present for the resource and also how to handle presence of no AceEntries for a resource.
   *
   * @param method what method is being performed.
   * @param resource what resource the method is being performed
   * @param principal who is performing the method on the resource.
   * @return {@code true} if principal has the permission to perform the method on the resource, otherwise return {@code false}.
   */
  public boolean canAccess(Method method, Resource resource, Principal principal);

  /**
   * Check if the principal has the permission to perform the method on the resource. Implementation should define how to handle
   * duplicate/conflicting ACE entries present for the resource and also how to handle presence of no AceEntries for a resource.
   *
   * @param method what method is being performed.
   * @param resource what resource the method is being performed
   * @param accessorCert who is performing the method on the resource.
   * @return {@code true} if principal has the permission to perform the method on the resource, otherwise return {@code false}.
   */
  public boolean canAccess(Method method, Resource resource, X509Certificate accessorCert);

  /**
   * Return a list of existing AceEntries present for the given resource.
   * Implementations should return an empty AclBinding object when no acl's are pesent for the resource.
   * @param resource
   * @return {@link AclBinding} object containg the list of existing aceEntries. It may be null if there is no existing ACL's provisioned.
   */
  public AclBinding describeAcls(Resource resource);

  /**
   * This will set the AceEntries in provided AclBinding object to be the current set of ACL's for the resource. This
   * performs an overwrite operation. An empty AceEntries list will clear all acls and achieve a similar result like {@code clearAcls}.
   *
   * @param aclBinding A fully contained object having a list of AceEntries associated with the resource.
   */
  public void setAcls(AclBinding aclBinding);

  /**
   * This will clear the existing AceEntries for a resource.
   *
   * @param resource the resource for which all ACl's will be cleared.
   */
  public void clearAcls(Resource resource);

  /**
   * This will add a single AceEntry to the existing AceEntries for a resource. Implementation may or may not allow duplicate/conflicting
   * entries. Implementation may throw any necessary error/exception.
   *
   * @param resource The resource for which an AceEntry is getting added.
   * @param aceEntry The AceEntry to be removed.
   */
  public void addAce(Resource resource, AceEntry aceEntry);

  /**
   * This will remove a single AceEntry from the existing AceEntries for a resource. Implementation should define how to handle
   * removal in case duplicate AceEntries are allowed. The equivalence check should do exact match of all fields of the AceEntry object.
   * Implementation may throw any necessary error/exception.
   *
   * @param resource The resource for which an AceEntry is getting removed.
   * @param aceEntry The AceEntry to be removed.
   */
  public void removeAce(Resource resource, AceEntry aceEntry);
}