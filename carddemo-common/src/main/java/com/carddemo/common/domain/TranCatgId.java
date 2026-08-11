package com.carddemo.common.domain;

import java.io.Serializable;
import java.util.Objects;

/**
 * Composite primary-key class for the ``TranCatg`` transaction-category entity.
 *
 * :purpose: Identifies a single transaction category by its two-part natural
 *     key: the transaction type code paired with the transaction category code.
 *     Mirrors the COBOL ``TRAN-CAT-KEY`` group of the ``CVTRA04Y`` copybook.
 * :output: An ``@IdClass`` value object whose field names and types match the
 *     ``@Id`` fields of ``TranCatg`` (``tranTypeCd``, ``tranCatCd``), so JPA can
 *     bind the compound identifier by name and type.
 */
public class TranCatgId implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * Transaction type code (source ``TRAN-TYPE-CD`` PIC X(02)).
     */
    private String tranTypeCd;

    /**
     * Transaction category code (source ``TRAN-CAT-CD`` PIC 9(04)).
     */
    private Integer tranCatCd;

    /**
     * No-argument constructor required by JPA to instantiate the identifier.
     */
    public TranCatgId() {
    }

    /**
     * All-arguments constructor.
     *
     * :param tranTypeCd: transaction type code (``TRAN-TYPE-CD``)
     * :param tranCatCd: transaction category code (``TRAN-CAT-CD``)
     */
    public TranCatgId(String tranTypeCd, Integer tranCatCd) {
        this.tranTypeCd = tranTypeCd;
        this.tranCatCd = tranCatCd;
    }

    /**
     * :purpose: Read ``tranTypeCd``.
     * :return: the transaction type code component of the key
     */
    public String getTranTypeCd() {
        return tranTypeCd;
    }

    /**
     * :purpose: Set ``tranTypeCd``.
     * :param tranTypeCd: the transaction type code component of the key
     */
    public void setTranTypeCd(String tranTypeCd) {
        this.tranTypeCd = tranTypeCd;
    }

    /**
     * :purpose: Read ``tranCatCd``.
     * :return: the transaction category code component of the key
     */
    public Integer getTranCatCd() {
        return tranCatCd;
    }

    /**
     * :purpose: Set ``tranCatCd``.
     * :param tranCatCd: the transaction category code component of the key
     */
    public void setTranCatCd(Integer tranCatCd) {
        this.tranCatCd = tranCatCd;
    }

    /**
     * Compares two identifiers on both key components.
     *
     * :param o: the object to compare against
     * :return: ``true`` when both key components are equal
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TranCatgId that = (TranCatgId) o;
        return Objects.equals(tranTypeCd, that.tranTypeCd)
                && Objects.equals(tranCatCd, that.tranCatCd);
    }

    /**
     * Derives the hash code from both key components.
     *
     * :return: a hash code consistent with {@link #equals(Object)}
     */
    @Override
    public int hashCode() {
        return Objects.hash(tranTypeCd, tranCatCd);
    }
}
