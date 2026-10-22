package br.com.verificador.desktop.config;

import br.com.verificador.desktop.engine.IcpBrasilEngine;
import br.com.verificador.desktop.engine.VerificationEngine;
import br.com.verificador.desktop.service.ReportExportService;
import br.com.verificador.desktop.service.TrustAnchorService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DesktopConfiguration {
    @Bean AppPaths paths() { return AppPaths.defaults(); }
    @Bean VerificationEngine verificationEngine(AppPaths paths) { return new IcpBrasilEngine(paths); }
    @Bean TrustAnchorService trustAnchorService(AppPaths paths) { return new TrustAnchorService(paths); }
    @Bean ReportExportService reportExportService() { return new ReportExportService(); }
}
