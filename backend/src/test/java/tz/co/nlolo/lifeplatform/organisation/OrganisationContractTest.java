package tz.co.nlolo.lifeplatform.organisation;

import com.atlassian.oai.validator.mockmvc.OpenApiValidationMatchers;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tz.co.nlolo.lifeplatform.Application;
import tz.co.nlolo.lifeplatform.MigrationTestSupport;
import tz.co.nlolo.lifeplatform.ObjectStoreContainer;
import tz.co.nlolo.lifeplatform.TenantContext;
import tz.co.nlolo.lifeplatform.organisation.api.DocumentKind;
import tz.co.nlolo.lifeplatform.organisation.api.OrganisationApi;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Settings → Organisation through the real stack (2026-11-09): who may change it, what it refuses, the branch register
 * taking over from the shared list, a logo and a signature stored for real, and a receipt preview drawn in them.
 */
@Testcontainers
@AutoConfigureMockMvc
@SpringBootTest(classes = Application.class, webEnvironment = SpringBootTest.WebEnvironment.MOCK)
class OrganisationContractTest {

    private static final String SPEC = "api/openapi/openapi-organisation.yaml";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final ObjectStoreContainer STORE = new ObjectStoreContainer();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("minio.endpoint", STORE::getS3URL);
        registry.add("minio.access-key", STORE::getUserName);
        registry.add("minio.secret-key", STORE::getPassword);
    }

    @BeforeAll
    static void migrate() throws Exception {
        MigrationTestSupport.applyMigration(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(),
            "db-migrations/audit/V1__create_audit_schema.sql",
            "db-migrations/audit/V2__rls_fail_closed.sql",
            "db-migrations/audit/V3__q4_2026_partitions.sql",
            "db-migrations/refdata/V1__create_refdata_schema.sql",
            "db-migrations/refdata/V8__ifrs17_branches_and_channels.sql",
            "db-migrations/document/V1__create_document_schema.sql",
            "db-migrations/document/V2__add_content_type_and_file_name.sql",
            "db-migrations/document/V10__branding_image_document_type.sql",
            "db-migrations/organisation/V1__organisation_settings.sql");
        MinioClient client = MinioClient.builder().endpoint(STORE.getS3URL())
            .credentials(STORE.getUserName(), STORE.getPassword()).build();
        client.makeBucket(MakeBucketArgs.builder().bucket("policy-documents").build());
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private OrganisationApi organisation;
    private final UUID tenant = UUID.randomUUID();

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    private RequestPostProcessor as(String role) {
        return jwt().authorities(new SimpleGrantedAuthority("ROLE_REALM_STAFF"), new SimpleGrantedAuthority("ROLE_" + role))
            .jwt(b -> b.subject(role.toLowerCase()).claim("tenant_id", tenant.toString()).claim("name", "Asha Admin"));
    }

    private static byte[] png(int w, int h, Color colour) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(colour);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    @Test
    void onlyAnAdministratorReadsOrChangesTheSettings() throws Exception {
        mockMvc.perform(get("/organisation/settings").with(as("FINANCE_OFFICER"))).andExpect(status().isForbidden());
        mockMvc.perform(put("/organisation/profile").with(as("FINANCE_OFFICER")).contentType("application/json")
            .content("{\"legalName\":\"X\"}")).andExpect(status().isForbidden());
        // Any staff member reads the branches, for the pickers.
        mockMvc.perform(get("/organisation/branches").with(as("FINANCE_OFFICER")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[0].headOffice").value(true));
        mockMvc.perform(get("/organisation/settings").with(as("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andExpect(jsonPath("$.profile.legalName").value("Nlolo Life"))
            .andExpect(jsonPath("$.branding.layout").value("CLASSIC"));
    }

    @Test
    void aSettingItCannotUseIsRefusedInWords() throws Exception {
        mockMvc.perform(put("/organisation/branding").with(as("ADMIN")).contentType("application/json").content("""
                {"primaryColour":"navy","secondaryColour":"#0E8C7F","typeface":"SANS","layout":"CLASSIC","paper":"A4",
                 "hasLogo":false}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("Colours are written #RRGGBB, for example #17324D."));
        mockMvc.perform(multipart("/organisation/logo").file(new MockMultipartFile("file", "logo.gif", "image/gif",
                new byte[] { 1, 2, 3 })).with(as("ADMIN")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.detail").value("The logo must be a PNG or JPEG image."));
    }

    /** The first branch a company saves copies the shared list in; nothing it already used disappears. */
    @Test
    void theBranchRegisterTakesOverFromTheSharedList() throws Exception {
        mockMvc.perform(put("/organisation/branches/MWZ").with(as("ADMIN")).contentType("application/json").content("""
                {"code":"MWZ","name":"Mwanza","address":"Kenyatta Road","active":true,"headOffice":false}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        mockMvc.perform(get("/organisation/branches").with(as("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[?(@.code=='DSM')].name").value(org.hamcrest.Matchers.hasItem("Dar es Salaam")))
            .andExpect(jsonPath("$[?(@.code=='MWZ')].address").value(org.hamcrest.Matchers.hasItem("Kenyatta Road")));
        TenantContext.set(tenant);
        assertThat(organisation.isActiveBranch("MWZ")).isTrue();
        assertThat(organisation.isActiveBranch("NOPE")).isFalse();
        // The head office cannot be closed.
        String headOffice = organisation.headOfficeBranch();
        TenantContext.clear();
        mockMvc.perform(put("/organisation/branches/" + headOffice).with(as("ADMIN")).contentType("application/json")
                .content("{\"code\":\"" + headOffice + "\",\"name\":\"Head\",\"active\":false,\"headOffice\":true}"))
            .andExpect(status().isBadRequest());
    }

    /** A logo and a signature stored for real, and a receipt drawn in them, signed by whoever signs receipts today. */
    @Test
    void theLetterheadAndTheSignatureReachTheDocuments() throws Exception {
        mockMvc.perform(put("/organisation/profile").with(as("ADMIN")).contentType("application/json").content("""
                {"legalName":"Bima Bora Life Assurance Ltd","tradingName":"Bima Bora","registrationNumber":"TIRA/LI/021",
                 "address":"Plot 12, Samora Avenue","phone":"+255 22 211 0000","email":"care@bimabora.co.tz"}"""))
            .andExpect(status().isOk())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC));
        mockMvc.perform(multipart("/organisation/logo").file(new MockMultipartFile("file", "logo.png", "image/png",
                png(200, 60, new Color(23, 50, 77)))).with(as("ADMIN")))
            .andExpect(status().isNoContent());
        String added = mockMvc.perform(post("/organisation/signatories").with(as("ADMIN")).contentType("application/json")
                .content("{\"name\":\"Neema Mushi\",\"title\":\"Head of Finance\",\"effectiveFrom\":\"2026-01-01\","
                    + "\"signs\":[\"RECEIPT\",\"PAYOUT_ADVICE\"]}"))
            .andExpect(status().isCreated())
            .andExpect(OpenApiValidationMatchers.openApi().isValid(SPEC))
            .andReturn().getResponse().getContentAsString();
        String id = com.jayway.jsonpath.JsonPath.read(added, "$.signatoryId");
        mockMvc.perform(multipart("/organisation/signatories/" + id + "/signature").file(new MockMultipartFile("file",
                "sig.png", "image/png", png(300, 90, Color.BLACK))).with(as("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.hasSignature").value(true));

        TenantContext.set(tenant);
        assertThat(organisation.letterhead().displayName()).isEqualTo("Bima Bora");
        assertThat(organisation.letterhead().logo()).isNotEmpty();
        assertThat(organisation.signatory(DocumentKind.RECEIPT, LocalDate.of(2026, 11, 9))).get()
            .satisfies(s -> assertThat(s.image()).isNotEmpty());
        // Before the signatory started, and for a document they do not sign: nobody.
        assertThat(organisation.signatory(DocumentKind.RECEIPT, LocalDate.of(2025, 12, 31))).isEmpty();
        assertThat(organisation.signatory(DocumentKind.POLICY_SCHEDULE, LocalDate.of(2026, 11, 9))).isEmpty();
        TenantContext.clear();

        byte[] preview = mockMvc.perform(post("/organisation/preview").with(as("ADMIN")).contentType("application/json")
                .content("""
                    {"profile":{"legalName":"Bima Bora Life Assurance Ltd","tradingName":"Bima Bora Draft"},
                     "branding":{"primaryColour":"#7A1F2B","secondaryColour":"#D4A017","typeface":"SERIF",
                                 "layout":"BANDED","paper":"A4","footerText":"Regulated by TIRA","hasLogo":true}}"""))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsByteArray();
        try (var doc = Loader.loadPDF(preview)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Bima Bora Draft", "Receipt RC-2026-000123",
                "Neema Mushi", "Head of Finance", "Regulated by TIRA", "Page 1 of 1");
        }
    }
}
