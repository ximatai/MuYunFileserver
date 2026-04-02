package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import net.ximatai.muyun.fileserver.application.DownloadFile;
import net.ximatai.muyun.fileserver.application.TokenFileQueryService;
import net.ximatai.muyun.fileserver.api.dto.FileMetadataResponse;
import net.ximatai.muyun.fileserver.common.api.ApiResponses;
import net.ximatai.muyun.fileserver.common.api.DownloadResponses;
import org.jboss.resteasy.reactive.RestPath;

@Path("/api/v1/public/files")
@Blocking
public class PublicFilesResource {

    @Inject
    TokenFileQueryService tokenFileQueryService;

    @GET
    @Path("/{fileId}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response getMetadata(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        FileMetadataResponse response = tokenFileQueryService.getMetadata(fileId, accessToken);
        return Response.ok(ApiResponses.ok(response)).build();
    }

    @GET
    @Path("/{fileId}/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@RestPath String fileId, @QueryParam("access_token") String accessToken) {
        DownloadFile file = tokenFileQueryService.openDownload(fileId, accessToken);
        return DownloadResponses.ok(file);
    }
}
