package net.ximatai.muyun.fileserver.infrastructure.persistence;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import net.ximatai.muyun.fileserver.domain.file.FileReception;
import net.ximatai.muyun.fileserver.domain.file.FileMetadata;
import net.ximatai.muyun.fileserver.common.exception.ConflictException;
import javax.sql.DataSource;
import java.sql.*;
import java.util.*;

@ApplicationScoped
public class FileReceptionRepository {
    @Inject DataSource source;
    @Inject JdbcFileMetadataRepository files;

    public FileReception create(FileReception value) {
        try (var c = source.getConnection(); var s = c.prepareStatement("""
                insert or ignore into file_reception
                (id,tenant_id,idempotency_key,prefix,state,max_objects,max_bytes,expires_at,bucket,ttl_seconds)
                values (?,?,?,?,?,?,?,?,?,?)
                """)) {
            s.setString(1,value.id()); s.setString(2,value.tenantId()); s.setString(3,value.idempotencyKey());
            s.setString(4,value.prefix()); s.setString(5,"OPEN"); s.setInt(6,value.maxObjects());
            s.setLong(7,value.maxBytes()); s.setLong(8,value.expiresAt());s.setString(9,value.bucket());s.setLong(10,value.ttlSeconds());s.executeUpdate();
            try (var q = c.prepareStatement("select * from file_reception where tenant_id=? and idempotency_key=?")) {
                q.setString(1,value.tenantId()); q.setString(2,value.idempotencyKey());
                try (var rows = q.executeQuery()) {
                    if (!rows.next()) throw new IllegalStateException("reception was not created");
                    return map(rows);
                }
            }
        } catch (SQLException e) { throw new IllegalStateException("failed to create reception",e); }
    }

    public Optional<FileReception> find(String id) {
        try (var c=source.getConnection(); var s=c.prepareStatement("select * from file_reception where id=?")) {
            s.setString(1,id);
            try (var rows=s.executeQuery()) { return rows.next()?Optional.of(map(rows)):Optional.empty(); }
        } catch (SQLException e) { throw new IllegalStateException("failed to query reception",e); }
    }

    public boolean claim(String id, String hash, String token, long now, long until) {
        return update("""
                update file_reception set state='CONFIRMING', manifest_hash=?, lease_token=?, lease_until=?
                where id=? and expires_at>? and (manifest_hash is null or manifest_hash=?)
                and (state='OPEN' or (state='CONFIRMING' and lease_until<?))
                """, hash,token,until,id,now,hash,now)>0;
    }

    public void renew(String id,String token,long now,long until) {
        if (update("""
                update file_reception set lease_until=? where id=? and state='CONFIRMING'
                and lease_token=? and lease_until>? and expires_at>?
                """,until,id,token,now,now)==0) throw new ConflictException("reception lease was lost");
    }

    public void release(String id,String token) {
        update("update file_reception set state='OPEN', lease_token=null, lease_until=null where id=? and state='CONFIRMING' and lease_token=?",id,token);
    }

    public void publish(String id,String token,long now,Map<String,FileMetadata> metadata) {
        try (var c=source.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var s=c.prepareStatement("""
                        update file_reception set state='READY', lease_token=null, lease_until=null
                        where id=? and state='CONFIRMING' and lease_token=? and lease_until>? and expires_at>?
                        """)) {
                    s.setString(1,id);s.setString(2,token);s.setLong(3,now);s.setLong(4,now);
                    if(s.executeUpdate()!=1) throw new ConflictException("reception lease was lost");
                }
                for(var entry:metadata.entrySet()) {
                    files.insert(c,entry.getValue());
                    try(var s=c.prepareStatement("insert into file_reception_asset(reception_id,object_key,file_id) values(?,?,?)")) {
                        s.setString(1,id);s.setString(2,entry.getKey());s.setString(3,entry.getValue().id());s.executeUpdate();
                    }
                }
                try(var s=c.prepareStatement("delete from file_reception_staged where reception_id=? and lease_token=?")) {
                    s.setString(1,id);s.setString(2,token);s.executeUpdate();
                }
                c.commit();
            } catch(Exception e) { c.rollback(); throw e; }
        } catch(SQLException e) { throw new IllegalStateException("failed to publish reception",e); }
    }

    public Map<String,String> assets(String id) {
        try(var c=source.getConnection();var s=c.prepareStatement("select object_key,file_id from file_reception_asset where reception_id=? order by object_key")) {
            s.setString(1,id);var values=new LinkedHashMap<String,String>();
            try(var rows=s.executeQuery()){while(rows.next())values.put(rows.getString(1),rows.getString(2));}
            return values;
        }catch(SQLException e){throw new IllegalStateException("failed to query reception assets",e);}
    }

    public boolean cancel(String id) { return update("update file_reception set state='CANCELLED' where id=? and state='OPEN'",id)>0; }

    public List<FileReception> expireAndFindCleanup(long now) {
        update("""
                update file_reception set state='EXPIRED',lease_token=null,lease_until=null
                where expires_at<=? and (state='OPEN' or (state='CONFIRMING' and lease_until<=?))
                """,now,now);
        try(var c=source.getConnection();var s=c.prepareStatement("""
                select * from file_reception where expires_at<=? and next_cleanup_at<=?
                and state in('EXPIRED','CANCELLED','READY') order by next_cleanup_at,expires_at limit 100
                """)) {
            s.setLong(1,now);s.setLong(2,now);var values=new ArrayList<FileReception>();
            try(var rows=s.executeQuery()){while(rows.next())values.add(map(rows));}return values;
        }catch(SQLException e){throw new IllegalStateException("failed to query reception cleanup",e);}
    }

    public void stage(String id,String token,String fileId,String key) {
        update("insert into file_reception_staged(file_id,reception_id,lease_token,storage_key) values(?,?,?,?)",fileId,id,token,key);
    }
    public Map<String,String> staged(String id) {
        return staged(id,null);
    }
    public Map<String,String> staged(String id,String token) {
        try(var c=source.getConnection();var s=c.prepareStatement("select file_id,storage_key from file_reception_staged where reception_id=?"+(token==null?"":" and lease_token=?"))) {
            s.setString(1,id);if(token!=null)s.setString(2,token);var values=new LinkedHashMap<String,String>();
            try(var r=s.executeQuery()){while(r.next())values.put(r.getString(1),r.getString(2));}return values;
        }catch(SQLException e){throw new IllegalStateException("failed to query staged reception files",e);}
    }
    public void unstage(String fileId){update("delete from file_reception_staged where file_id=?",fileId);}
    public void cleaned(String id,long next){update("update file_reception set next_cleanup_at=? where id=?",next,id);}

    private int update(String sql,Object... args) {
        try(var c=source.getConnection();var s=c.prepareStatement(sql)) {
            for(int i=0;i<args.length;i++)s.setObject(i+1,args[i]);return s.executeUpdate();
        }catch(SQLException e){throw new IllegalStateException("failed to update reception",e);}
    }
    private FileReception map(ResultSet r)throws SQLException {
        return new FileReception(r.getString("id"),r.getString("tenant_id"),r.getString("idempotency_key"),
                r.getString("prefix"),r.getString("state"),r.getInt("max_objects"),r.getLong("max_bytes"),
                r.getLong("expires_at"),r.getString("manifest_hash"),r.getString("lease_token"),
                r.getObject("lease_until")==null?null:r.getLong("lease_until"),r.getString("bucket"),r.getLong("ttl_seconds"));
    }
}
