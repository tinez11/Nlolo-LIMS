<#--
  The frame every Life Platform email is sent in (2026-10-28): the name the email comes from, the message, and a line
  saying nobody reads replies. Inline styles and a table only -- what every mail client still renders. Base's frame
  was a bare <html><body>, so a reset email arrived as unbranded default-font text and looked like phishing.
-->
<#macro emailLayout>
<html>
<body style="margin:0;padding:0;background:#f1f6f6;">
<table role="presentation" width="100%" cellpadding="0" cellspacing="0" style="background:#f1f6f6;">
  <tr>
    <td align="center" style="padding:24px 12px;">
      <table role="presentation" width="100%" cellpadding="0" cellspacing="0"
             style="max-width:560px;background:#ffffff;border:1px solid #dde6e8;border-radius:12px;">
        <tr>
          <td style="padding:24px 28px 8px;font-family:Arial,Helvetica,sans-serif;font-size:16px;font-weight:bold;color:#0f6e64;">
            Nlolo Life
          </td>
        </tr>
        <tr>
          <td style="padding:8px 28px 24px;font-family:Arial,Helvetica,sans-serif;font-size:15px;line-height:1.55;color:#1f2a33;">
            <#nested>
          </td>
        </tr>
      </table>
      <p style="margin:16px 0 0;font-family:Arial,Helvetica,sans-serif;font-size:12px;color:#6b7680;">
        ${msg("lpEmailFooter")}
      </p>
    </td>
  </tr>
</table>
</body>
</html>
</#macro>
