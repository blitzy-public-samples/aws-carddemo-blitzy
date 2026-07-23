/*
 * Copyright Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.aws.carddemo.dto.screen;

import jakarta.validation.constraints.Size;

/**
 * Main menu screen form.
 *
 * <p>Origin: legacy/cpy-bms/COMEN01.CPY (BMS mapset COMEN01, map COMEN1A).
 *
 * <p>Form-backing bean for the AWS CardDemo main (user) menu screen, driven by
 * CICS transaction {@code CM00} and program {@code COMEN01C}. This plain Java
 * DTO is a faithful translation of the BMS symbolic map copybook: it models
 * exactly one {@link String} property per BMS <em>value</em> field
 * ({@code <name>I} / {@code <name>O}), preserving the original 24x80
 * field/label/length contract of the 3270 screen.
 *
 * <p>Attribute and plumbing bytes (length {@code L}, flag {@code F}, attribute
 * {@code A}, color {@code C}, position {@code P}, highlight {@code H} and
 * validation {@code V}) are intentionally not modeled here; those concerns are
 * handled by the Thymeleaf view layer and the presentation utilities.
 *
 * <p>Every property length mirrors the corresponding COBOL {@code PIC X(n)}
 * clause and is enforced with {@link Size}. No monetary values appear on this
 * screen, so no decimal types are declared.
 */
public class COMEN01Form {

    /** BMS TRNNAME, PIC X(4) &mdash; transaction identifier shown in the header. */
    @Size(max = 4)
    private String trnname;

    /** BMS TITLE01, PIC X(40) &mdash; first title line in the header. */
    @Size(max = 40)
    private String title01;

    /** BMS CURDATE, PIC X(8) &mdash; current date shown in the header. */
    @Size(max = 8)
    private String curdate;

    /** BMS PGMNAME, PIC X(8) &mdash; owning program name shown in the header. */
    @Size(max = 8)
    private String pgmname;

    /** BMS TITLE02, PIC X(40) &mdash; second title line in the header. */
    @Size(max = 40)
    private String title02;

    /** BMS CURTIME, PIC X(8) &mdash; current time shown in the header. */
    @Size(max = 8)
    private String curtime;

    /** BMS OPTN001, PIC X(40) &mdash; menu option display line 1. */
    @Size(max = 40)
    private String optn001;

    /** BMS OPTN002, PIC X(40) &mdash; menu option display line 2. */
    @Size(max = 40)
    private String optn002;

    /** BMS OPTN003, PIC X(40) &mdash; menu option display line 3. */
    @Size(max = 40)
    private String optn003;

    /** BMS OPTN004, PIC X(40) &mdash; menu option display line 4. */
    @Size(max = 40)
    private String optn004;

    /** BMS OPTN005, PIC X(40) &mdash; menu option display line 5. */
    @Size(max = 40)
    private String optn005;

    /** BMS OPTN006, PIC X(40) &mdash; menu option display line 6. */
    @Size(max = 40)
    private String optn006;

    /** BMS OPTN007, PIC X(40) &mdash; menu option display line 7. */
    @Size(max = 40)
    private String optn007;

    /** BMS OPTN008, PIC X(40) &mdash; menu option display line 8. */
    @Size(max = 40)
    private String optn008;

    /** BMS OPTN009, PIC X(40) &mdash; menu option display line 9. */
    @Size(max = 40)
    private String optn009;

    /** BMS OPTN010, PIC X(40) &mdash; menu option display line 10. */
    @Size(max = 40)
    private String optn010;

    /** BMS OPTN011, PIC X(40) &mdash; menu option display line 11. */
    @Size(max = 40)
    private String optn011;

    /** BMS OPTN012, PIC X(40) &mdash; menu option display line 12. */
    @Size(max = 40)
    private String optn012;

    /** BMS OPTION, PIC X(2) &mdash; the user's menu option selection (input). */
    @Size(max = 2)
    private String option;

    /** BMS ERRMSG, PIC X(78) &mdash; error / informational message line. */
    @Size(max = 78)
    private String errmsg;

    /**
     * Creates an empty {@code COMEN01Form} with all fields unset. Required for
     * form binding and framework instantiation.
     */
    public COMEN01Form() {
    }

    public String getTrnname() {
        return trnname;
    }

    public void setTrnname(String trnname) {
        this.trnname = trnname;
    }

    public String getTitle01() {
        return title01;
    }

    public void setTitle01(String title01) {
        this.title01 = title01;
    }

    public String getCurdate() {
        return curdate;
    }

    public void setCurdate(String curdate) {
        this.curdate = curdate;
    }

    public String getPgmname() {
        return pgmname;
    }

    public void setPgmname(String pgmname) {
        this.pgmname = pgmname;
    }

    public String getTitle02() {
        return title02;
    }

    public void setTitle02(String title02) {
        this.title02 = title02;
    }

    public String getCurtime() {
        return curtime;
    }

    public void setCurtime(String curtime) {
        this.curtime = curtime;
    }

    public String getOptn001() {
        return optn001;
    }

    public void setOptn001(String optn001) {
        this.optn001 = optn001;
    }

    public String getOptn002() {
        return optn002;
    }

    public void setOptn002(String optn002) {
        this.optn002 = optn002;
    }

    public String getOptn003() {
        return optn003;
    }

    public void setOptn003(String optn003) {
        this.optn003 = optn003;
    }

    public String getOptn004() {
        return optn004;
    }

    public void setOptn004(String optn004) {
        this.optn004 = optn004;
    }

    public String getOptn005() {
        return optn005;
    }

    public void setOptn005(String optn005) {
        this.optn005 = optn005;
    }

    public String getOptn006() {
        return optn006;
    }

    public void setOptn006(String optn006) {
        this.optn006 = optn006;
    }

    public String getOptn007() {
        return optn007;
    }

    public void setOptn007(String optn007) {
        this.optn007 = optn007;
    }

    public String getOptn008() {
        return optn008;
    }

    public void setOptn008(String optn008) {
        this.optn008 = optn008;
    }

    public String getOptn009() {
        return optn009;
    }

    public void setOptn009(String optn009) {
        this.optn009 = optn009;
    }

    public String getOptn010() {
        return optn010;
    }

    public void setOptn010(String optn010) {
        this.optn010 = optn010;
    }

    public String getOptn011() {
        return optn011;
    }

    public void setOptn011(String optn011) {
        this.optn011 = optn011;
    }

    public String getOptn012() {
        return optn012;
    }

    public void setOptn012(String optn012) {
        this.optn012 = optn012;
    }

    public String getOption() {
        return option;
    }

    public void setOption(String option) {
        this.option = option;
    }

    public String getErrmsg() {
        return errmsg;
    }

    public void setErrmsg(String errmsg) {
        this.errmsg = errmsg;
    }

    /**
     * BMS {@code ERRMSGC} &mdash; the colour attribute of the {@link #errmsg} line, reproducing the
     * COBOL {@code MOVE DFHxxx TO ERRMSGC OF COMEN1AO}. Holds the semantic 3270 colour token the
     * template uses to pick the message colour class: {@code "red"} (error; the BMS map default
     * {@code COLOR=RED}), {@code "green"} (COBOL {@code DFHGREEN}, the "coming soon" informational
     * line), or {@code "neutral"} (COBOL {@code DFHNEUTR}). Render-only (never a bound input;
     * excluded from the {@code @InitBinder} allow-list); defaults to {@code "red"} so any path that
     * does not explicitly set a colour reproduces the legacy default red line.
     */
    private String errmsgColor = "red";

    public String getErrmsgColor() {
        return errmsgColor;
    }

    public void setErrmsgColor(String errmsgColor) {
        this.errmsgColor = errmsgColor;
    }

    @Override
    public String toString() {
        return "COMEN01Form{"
                + "trnname='" + trnname + '\''
                + ", title01='" + title01 + '\''
                + ", curdate='" + curdate + '\''
                + ", pgmname='" + pgmname + '\''
                + ", title02='" + title02 + '\''
                + ", curtime='" + curtime + '\''
                + ", optn001='" + optn001 + '\''
                + ", optn002='" + optn002 + '\''
                + ", optn003='" + optn003 + '\''
                + ", optn004='" + optn004 + '\''
                + ", optn005='" + optn005 + '\''
                + ", optn006='" + optn006 + '\''
                + ", optn007='" + optn007 + '\''
                + ", optn008='" + optn008 + '\''
                + ", optn009='" + optn009 + '\''
                + ", optn010='" + optn010 + '\''
                + ", optn011='" + optn011 + '\''
                + ", optn012='" + optn012 + '\''
                + ", option='" + option + '\''
                + ", errmsg='" + errmsg + '\''
                + '}';
    }
}
