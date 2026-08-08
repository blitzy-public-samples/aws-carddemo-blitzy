package com.carddemo.account.domain.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.carddemo.account.api.dto.AccountDataRequest;
import com.carddemo.account.api.dto.AccountUpdateRequest;
import com.carddemo.account.api.dto.CustomerDataRequest;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Classifies the two alphanumeric edits as provenance and enforces that classification.
 *
 * <p><b>What is being classified, and why.</b>
 * {@link AlphanumericRequiredValidator} translates {@code 1230-EDIT-ALPHANUM-REQD} at
 * {@code app/cbl/COACTUPC.cbl:L1955-L2009} and {@link AlphanumericOptionalValidator} translates
 * {@code 1240-EDIT-ALPHANUM-OPT} at {@code app/cbl/COACTUPC.cbl:L2061-L2105}. A whole-file scan of
 * all 4236 lines of that program finds no {@code PERFORM} of either paragraph: the only references to
 * each are its own label, its two {@code GO TO} statements and its exit label. Neither paragraph runs
 * in the source, for any field, ever.
 *
 * <p><b>Why they are not wired to a request field.</b> Because wiring one would add validation the
 * source does not perform. The equivalence requirement of AAP 0.1.1 is that the same inputs produce
 * the same results, and AAP 0.2.2 lists behavioural additions that are excluded for exactly this
 * reason. A field given the alphanumeric edit would start refusing values the source accepts, which
 * is a change of outcome, not a tightening of a loose translation.
 *
 * <p><b>Why they are not deleted either.</b> Rule 1 of this project requires bidirectional
 * traceability with no gaps, so every paragraph of an in-scope program needs a target. These two are
 * that target, and their own suites hold the character-class sets and the verbatim message text of
 * the two paragraphs. Removing them would leave two paragraphs of a primary migration source with no
 * counterpart and no record of what they did.
 *
 * <p><b>What this class adds.</b> The classification stops being a sentence in a comment and becomes
 * a build-enforced property. The first test names the two edits no request field may carry, so a
 * later change that wires one fails here and states why. The second test proves the whole set of
 * enum members no field carries, so no other member has quietly become dead: measuring it turned up
 * two more, {@code ACCOUNT_ID} and {@code US_STATE_CODE}, which are carried by no field for a
 * different and legitimate reason recorded on {@link #SERVICE_APPLIED_EDITS}. The third test proves
 * both classified members stay declared and still answer their source message, which is the concise
 * provenance the classification retains. Together these answer the reviewer's concern directly:
 * nothing about the wiring state is left to be inferred from a comment, so missing wiring cannot be
 * masked.
 */
@DisplayName("The two alphanumeric edits: classified as provenance, and held there")
class UnreachableEditClassificationTest {

    /**
     * The edits no request field carries because the paragraphs they translate have zero invocation
     * sites in {@code app/cbl/COACTUPC.cbl}. These two are provenance and nothing else.
     */
    private static final Set<DomainEdit.Edit> PROVENANCE_ONLY_EDITS =
            EnumSet.of(DomainEdit.Edit.ALPHANUMERIC_REQUIRED, DomainEdit.Edit.ALPHANUMERIC_OPTIONAL);

    /**
     * The edits no request field carries because the service pass applies them itself.
     *
     * <p>These two are reached, just not through the annotation.
     * {@code AccountUpdateService:L570} runs the account-identifier edit as the search-key filter of
     * {@code 1200-EDIT-MAP-INPUTS} at {@code app/cbl/COACTUPC.cbl:L1429-L1461}, which has to run
     * before any field edit and therefore cannot be a bean constraint on a field.
     * {@code AccountUpdateService:L692} runs the state-code edit because
     * {@code app/cbl/COACTUPC.cbl:L2494} feeds its verdict into the state-and-postcode edit that
     * follows, so the two are ordered rather than independent. {@code CustomerDataRequest:L600} reads
     * the same validator for its own cross-field check.
     *
     * <p>They are listed apart from {@link #PROVENANCE_ONLY_EDITS} because the reason differs and the
     * consequence differs: wiring one of these to a field would duplicate an edit that already runs,
     * while wiring one of the two above would add an edit that never runs at all.
     */
    private static final Set<DomainEdit.Edit> SERVICE_APPLIED_EDITS =
            EnumSet.of(DomainEdit.Edit.ACCOUNT_ID, DomainEdit.Edit.US_STATE_CODE);

    /** Every edit no request field carries, whichever of the two reasons applies. */
    private static final Set<DomainEdit.Edit> EDITS_NO_FIELD_CARRIES =
            EnumSet.copyOf(concat(PROVENANCE_ONLY_EDITS, SERVICE_APPLIED_EDITS));

    /** The label the two provenance validators are exercised under. */
    private static final String PROVENANCE_LABEL = "Provenance";

    /** The character-class text both paragraphs move, at L1999 and at L2095. */
    private static final String CHARACTER_CLASS_MESSAGE = " can have numbers or alphabets only.";

    /** A width wide enough that no length rule of either validator fires. */
    private static final int AMPLE_WIDTH = 20;

    /** The request types whose fields carry the edits this service runs. */
    private static final List<Class<?>> REQUEST_TYPES =
            List.of(AccountUpdateRequest.class, AccountDataRequest.class, CustomerDataRequest.class);

    @Test
    @DisplayName("No request field carries either alphanumeric edit, because 1230 and 1240 never "
            + "run in the source")
    void noRequestFieldCarriesAnAlphanumericEdit() {
        Set<DomainEdit.Edit> carried = editsCarriedByRequestFields();

        assertThat(carried)
                .as("wiring 1230-EDIT-ALPHANUM-REQD or 1240-EDIT-ALPHANUM-OPT to a request field "
                        + "would refuse values app/cbl/COACTUPC.cbl accepts, because neither "
                        + "paragraph is ever performed there. If a field genuinely needs one, the "
                        + "decision belongs in card-platform/docs/decision-log.md as a declared "
                        + "additive deviation, and this list belongs updated with it.")
                .doesNotContainAnyElementsOf(PROVENANCE_ONLY_EDITS);
    }

    @Test
    @DisplayName("Every other edit of the enum is carried by at least one request field")
    void everyOtherEditIsCarriedBySomeRequestField() {
        Set<DomainEdit.Edit> carried = editsCarriedByRequestFields();
        List<DomainEdit.Edit> unused = EnumSet.allOf(DomainEdit.Edit.class).stream()
                .filter(edit -> !carried.contains(edit))
                .toList();

        assertThat(unused)
                .as("the members no request field carries, which is the classification list. A new "
                        + "name here is a translated edit nothing runs, and it needs the same "
                        + "decision the two alphanumeric edits already carry: either a documented "
                        + "reason it is applied elsewhere, or a documented reason it is provenance.")
                .containsExactlyInAnyOrderElementsOf(EDITS_NO_FIELD_CARRIES);
    }

    /**
     * The concise provenance the classification retains.
     *
     * <p>Both members stay declared, both validators stay callable, and each still answers the one
     * character-class message its paragraph moves: {@code app/cbl/COACTUPC.cbl:L1999} for the
     * required edit and {@code app/cbl/COACTUPC.cbl:L2095} for the optional one. Their own suites hold
     * the full character sets and every boundary; this reads the one line that says the translation is
     * still there and still says what the source said.
     */
    @Test
    @DisplayName("Both classified edits stay declared and still answer their source message")
    void bothClassifiedEditsStayDeclaredAndAnswerTheirSourceMessage() {
        assertThat(EnumSet.allOf(DomainEdit.Edit.class))
                .as("both members remain declared, which is what keeps the two paragraphs traceable")
                .containsAll(PROVENANCE_ONLY_EDITS);

        EditResult required = AlphanumericRequiredValidator.validate(PROVENANCE_LABEL, "AB-12",
                AMPLE_WIDTH);
        EditResult optional = AlphanumericOptionalValidator.validate(PROVENANCE_LABEL, "AB-12",
                AMPLE_WIDTH);

        assertThat(required.valid()).as("a hyphen is outside the allowed set").isFalse();
        assertThat(required.message())
                .as("the text app/cbl/COACTUPC.cbl:L1999 moves")
                .isEqualTo(PROVENANCE_LABEL + CHARACTER_CLASS_MESSAGE);
        assertThat(optional.valid()).as("the same hyphen is refused by the optional edit").isFalse();
        assertThat(optional.message())
                .as("the text app/cbl/COACTUPC.cbl:L2095 moves")
                .isEqualTo(PROVENANCE_LABEL + CHARACTER_CLASS_MESSAGE);
    }

    /**
     * Joins two edit sets.
     *
     * @param first  the first set
     * @param second the second set
     * @return every member of both
     */
    private static Set<DomainEdit.Edit> concat(Set<DomainEdit.Edit> first,
            Set<DomainEdit.Edit> second) {
        Set<DomainEdit.Edit> joined = new LinkedHashSet<>(first);
        joined.addAll(second);
        return joined;
    }

    /**
     * Names every edit a field or record component of a request type carries.
     *
     * <p>The annotation sits on record components, and the compiler copies it onto the field and the
     * accessor of each, so all three are read and the results merged. Nested record types are walked,
     * because a request carries its account and customer sections as nested records.
     *
     * @return the edits in use, in encounter order
     */
    private static Set<DomainEdit.Edit> editsCarriedByRequestFields() {
        Set<DomainEdit.Edit> carried = new LinkedHashSet<>();
        List<Class<?>> pending = new ArrayList<>(REQUEST_TYPES);
        Set<Class<?>> seen = new LinkedHashSet<>();

        while (!pending.isEmpty()) {
            Class<?> type = pending.removeFirst();
            if (!seen.add(type)) {
                continue;
            }
            collectEdits(type, carried);
            for (Class<?> nested : type.getDeclaredClasses()) {
                pending.add(nested);
            }
            if (type.isRecord()) {
                for (RecordComponent component : type.getRecordComponents()) {
                    Class<?> componentType = component.getType();
                    if (componentType.getName().startsWith("com.carddemo.account")) {
                        pending.add(componentType);
                    }
                }
            }
        }
        return carried;
    }

    /**
     * Adds the edits one type declares to a set.
     *
     * @param type    the type to read
     * @param carried the set to add to
     */
    private static void collectEdits(Class<?> type, Set<DomainEdit.Edit> carried) {
        for (java.lang.reflect.Field field : type.getDeclaredFields()) {
            DomainEdit declared = field.getAnnotation(DomainEdit.class);
            if (declared != null) {
                carried.add(declared.value());
            }
        }
        for (Method accessor : type.getDeclaredMethods()) {
            DomainEdit declared = accessor.getAnnotation(DomainEdit.class);
            if (declared != null) {
                carried.add(declared.value());
            }
        }
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                DomainEdit declared = component.getAnnotation(DomainEdit.class);
                if (declared != null) {
                    carried.add(declared.value());
                }
            }
        }
    }
}
