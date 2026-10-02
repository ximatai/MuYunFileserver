package net.ximatai.muyun.fileserver.api;

import io.smallrye.common.annotation.Blocking;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import net.ximatai.muyun.fileserver.application.FileReceptionService;
import net.ximatai.muyun.fileserver.api.dto.FileReceptionRequests.*;
import net.ximatai.muyun.fileserver.common.api.ApiResponses;
import net.ximatai.muyun.fileserver.common.security.RequireIdentity;

@Path("/api/v1/internal/receptions")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequireIdentity
@Blocking
public class FileReceptionsResource {
    @Inject FileReceptionService service;
    @POST public Object create(@HeaderParam("Authorization")String bearer,Create request){return ApiResponses.ok(service.create(service.authorize(bearer),request));}
    @GET @Path("/{id}") public Object get(@HeaderParam("Authorization")String bearer,@PathParam("id")String id){return ApiResponses.ok(service.get(service.authorize(bearer),id));}
    @GET @Path("/{id}/objects") public Object objects(@HeaderParam("Authorization")String bearer,@PathParam("id")String id)throws Exception{return ApiResponses.ok(service.list(service.authorize(bearer),id));}
    @PUT @Path("/{id}/objects/{key:.+}") @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Object put(@HeaderParam("Authorization")String bearer,@PathParam("id")String id,@PathParam("key")String key,java.io.InputStream input)throws Exception {
        service.put(service.authorize(bearer),id,key,input);return ApiResponses.ok(java.util.Map.of("accepted",true));
    }
    @POST @Path("/{id}/confirm") public Object confirm(@HeaderParam("Authorization")String bearer,@PathParam("id")String id,Confirm request)throws Exception{return ApiResponses.ok(service.confirm(service.authorize(bearer),id,request));}
    @POST @Path("/{id}/cancel") public Object cancel(@HeaderParam("Authorization")String bearer,@PathParam("id")String id){return ApiResponses.ok(service.cancel(service.authorize(bearer),id));}
}
