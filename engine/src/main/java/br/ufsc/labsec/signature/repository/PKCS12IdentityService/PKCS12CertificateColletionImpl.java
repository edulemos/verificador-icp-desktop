package br.ufsc.labsec.signature.repository.PKCS12IdentityService;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.security.*;
import java.security.cert.*;
import java.security.cert.Certificate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

import br.ufsc.labsec.component.Application;
import br.ufsc.labsec.signature.AlgorithmIdentifierMapper;
import br.ufsc.labsec.signature.CertificateCollection;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cms.CMSSignedGenerator;
import org.bouncycastle.util.encoders.Hex;

public class PKCS12CertificateColletionImpl implements CertificateCollection {

	private final Map<String, Certificate> certificateList = new ConcurrentHashMap<>();
	private MessageDigest digest;
	private static final String DIGEST_ALG = AlgorithmIdentifierMapper.getAlgorithmNameFromIdentifier(CMSSignedGenerator.DIGEST_SHA256);

	/**
	 * Definição de prefixos e sufixos esperados nos nomes dos arquivos.
	 */
	private static final String PREFIX = "cert-";
	private static final String CRT_SUFFIX = ".crt";
	private static final String CER_SUFFIX = ".cer";
	private static final String PEM_SUFFIX = ".pem";

	{
		try {
			digest = MessageDigest.getInstance(DIGEST_ALG);
		} catch (NoSuchAlgorithmException e) {
			Application.logger.log(Level.SEVERE, e.getMessage());
		}
	}

	private final Set<String> oldRepositoryPath = new HashSet<>();
	private PKCS12Repository pkcs12Repository;

	/**
	 * Definição de mensagens para erros inesperados ao lidar com carregamento e armazenamento de arquivos.
	 */
	private static final String ERROR_SAVING_CERTIFICATE = "Não foi possivel salvar o certificado.";
	private static final String ERROR_POPULATING_CACHE = "Erro preencher cache com certificado.";

	/**
	 * Construtor
	 * @param pkcs12Repository Repositório com certificados a ser carregado a coleção
	 */
	public PKCS12CertificateColletionImpl(PKCS12Repository pkcs12Repository) {
		loadCertificates(pkcs12Repository);
	}

	/**
	 * Retorna os primeiro 32 caracteres do hash da extensão AuthorityKeyIdentifier em base 16.
	 * @param certificate certificado.
	 * @return String com os primeiros 32 caracteres do hash em base 16.
	 */
	private String getAuthorityKeyIdentifierHash(X509Certificate certificate) {
		byte[] akiEncoded = certificate.getExtensionValue(Extension.authorityKeyIdentifier.getId());
		if (akiEncoded != null) {
			digest.reset();
			digest.update(akiEncoded);
			return Hex.toHexString(digest.digest()).substring(0, 32);
		}
		return null;
	}

	/**
	 * Carrega os certificados do repositório especificado para a lista de certificados desta instância
	 * desta coleção.
	 * @param pkcs12Repository {@link PKCS12Repository}, Repositório de certificados.
	 */
	private void loadCertificates(PKCS12Repository pkcs12Repository) {
		this.pkcs12Repository = pkcs12Repository;
		this.verifyCertificatePath();
	}

	/**
	 * Encontra um certificado na coleção a partir de um seletor.
	 * @param certSelector Seletor de certificado.
	 * @return Certificado encontrado, com valor nulo caso não esteja presente.
	 */
	public Certificate getCertificate(CertSelector certSelector) {
		this.verifyCertificatePath();
		for (Certificate cert : certificateList.values()) {
			if (certSelector.match(cert))
				return cert;

		}
		return null;
	}

	/**
	 * Carrega os certificados do endereço especificado para a lista de certificados desta instância
	 * desta coleção.
	 * @param path Endereço do diretório com os certificados a serem carregados.
	 */
	private void loadCertificates(String path) {
		if (path != null) {
			oldRepositoryPath.add(path);

			File pathFile = new File(path);
			if (!pathFile.exists()) {
				pathFile.mkdirs();
			}
			String[] files;
			FilenameFilter filter = new FilenameFilter() {
				@Override
				public boolean accept(File pathFile, String fileName) {
					return fileName.endsWith(PEM_SUFFIX) || fileName.endsWith(CER_SUFFIX) || fileName.endsWith(CRT_SUFFIX);
				}
			};
			files = pathFile.list(filter);
			CertificateFactory certFactory = null;

			try {
				certFactory = CertificateFactory.getInstance("X509", new org.bouncycastle.jce.provider.BouncyCastleProvider());
			} catch (CertificateException e) {
				Application.logger.log(Level.WARNING, ERROR_POPULATING_CACHE);
			}

			if (files != null && certFactory != null) {
				for (String file : files) {
					try (InputStream stream = new FileInputStream(path + File.separator + file)) {
						X509Certificate certificate = (X509Certificate) certFactory.generateCertificate(stream);
						String hash = Hex.toHexString(digest.digest(certificate.getEncoded()));
						certificateList.put(hash, certificate);
					} catch (CertificateException | IOException e) {
						Application.logger.log(Level.WARNING, ERROR_POPULATING_CACHE);
					}
				}
			}
		}
	}

	/**
	 * Carrega os certificados do repositório se ainda não carregado.
	 */
	private void verifyCertificatePath() {
		String path = this.pkcs12Repository.getRepositoryPath();
		synchronized (oldRepositoryPath) {
			if (!oldRepositoryPath.contains(path)) {
				this.loadCertificates(path);
			}
		}
	}

	/**
	 * Retorna a lista de certificados presentes nesta coleção
	 * @return Lista de {@link Certificate}.
	 */
	public List<Certificate> getCertificateList() {
		this.verifyCertificatePath();
		return new ArrayList<>(certificateList.values());
	}

	@Override
	/**
	 * Procura na coleção o emissor de um certificado.
	 * @param certificate Certificado emitido.
	 * @return Certificado emissor.
	 */
	public X509Certificate getIssuerCertificate(X509Certificate certificate) {
		for (Certificate value : certificateList.values()) {
            X509Certificate candidate = (X509Certificate) value;
            if (!certificate.getIssuerX500Principal().equals(candidate.getSubjectX500Principal())) continue;
            if (candidate.getBasicConstraints() < 0) continue;
            try {
                certificate.verify(candidate.getPublicKey(), new org.bouncycastle.jce.provider.BouncyCastleProvider());
                return candidate;
            } catch (GeneralSecurityException ignored) { }
        }
        return null;
	}

	@Override
	/**
	 * Adiciona lista de certificados á coleção se possível.
	 * Os certificados adicionados são candidatos à construção de cadeias; não são âncoras de confiança.
	 * @param lista de certificados.
	 */
	public void addCertificates(List<X509Certificate> certificates) {
        for (X509Certificate certificate : certificates) {
            try { certificateList.put(Hex.toHexString(digest.digest(certificate.getEncoded())), certificate); }
            catch (CertificateEncodingException exception) { Application.logger.log(Level.WARNING, ERROR_POPULATING_CACHE, exception); }
        }
    }

	@Override
	/**
	 * Adiciona o caminho de certificados à coleção.
	 * @param cadeia de certificados em lista ordenada.
	 */
	public void addCertPath(List<X509Certificate> certPath) {
		if (certPath != null) {
			X509Certificate lastCert = null;
			for (X509Certificate x509Certificate : certPath) {
				if (lastCert != null) {
					String akiHash = getAuthorityKeyIdentifierHash(lastCert);
					if (akiHash != null && !certificateList.containsKey(akiHash)) {
						certificateList.put(akiHash, x509Certificate);
						try {
							String fileName = PREFIX + akiHash + CRT_SUFFIX;
							File file = new File(this.pkcs12Repository.getRepositoryPath() + File.separator + fileName);
							if (!file.exists() && file.createNewFile()) {
								FileOutputStream out = new FileOutputStream(file);
								out.write(x509Certificate.getEncoded());
								out.close();
							}
						} catch (IOException | CertificateEncodingException e) {
							Application.logger.log(Level.SEVERE, ERROR_SAVING_CERTIFICATE, e);
						}
					}
				}
				lastCert = x509Certificate;
			}
		}
	}
}
