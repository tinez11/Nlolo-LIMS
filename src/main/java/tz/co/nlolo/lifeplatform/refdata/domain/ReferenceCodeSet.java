package tz.co.nlolo.lifeplatform.refdata.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reference_code_set", schema = "refdata")
public class ReferenceCodeSet {

    @Id
    @GeneratedValue
    @Column(name = "reference_code_set_id")
    private UUID id;

    @Column(name = "code_set_key", nullable = false)
    private String codeSetKey;

    @Column(name = "code", nullable = false)
    private String code;

    @Column(name = "label", nullable = false)
    private String label;

    @Column(name = "value", nullable = false)
    private String value;

    @Column(name = "jurisdiction")
    private String jurisdiction;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected ReferenceCodeSet() {}

    public String getCode() { return code; }
    public String getLabel() { return label; }
    public String getValue() { return value; }
    public String getJurisdiction() { return jurisdiction; }
}
