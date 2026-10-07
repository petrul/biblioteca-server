require 'uri'
require 'open3'
require 'fileutils'

module MinioMount
  module_function

  def mountpoint(profile, home = Dir.home)
    raise 'Invalid Biblioteca profile name' unless profile.to_s.match?(/\A[\w-]+\z/)
    File.join(home, 's3-mount', "biblioteca-#{profile}")
  end

  def configuration(env)
    url = URI.parse(env.fetch('MINIO_URL', '').strip)
    unless %w[http https].include?(url.scheme) && url.host && !url.userinfo && !url.query && !url.fragment
      raise 'MINIO_URL must be an HTTP(S) S3 API URL ending with the bucket name'
    end
    bucket = url.path.to_s.sub(%r{\A/}, '').sub(%r{/\z}, '')
    unless bucket.match?(/\A[a-z0-9][a-z0-9.-]*\z/)
      raise 'MINIO_URL must contain exactly one bucket path, for example http://host:9000/biblioteca'
    end
    key, secret = env.fetch('MINIO_CREDS', '').strip.split(':', 2)
    if key.to_s.empty? || secret.to_s.empty? || [key, secret].any? { |value| value.match?(/[\r\n]/) }
      raise 'MINIO_CREDS must contain access-key:secret-key'
    end
    url.path = ''
    [url.to_s, bucket, key, secret]
  rescue URI::InvalidURIError
    raise 'MINIO_URL is not a valid S3 API URL'
  end

  def mounted?(path)
    system('mountpoint', '-q', '--', path)
  end

  def open_directory(path)
    opener = %w[open xdg-open].find do |command|
      ENV.fetch('PATH', '').split(File::PATH_SEPARATOR).any? do |directory|
        executable = File.join(directory, command)
        File.file?(executable) && File.executable?(executable)
      end
    end
    unless opener
      warn "No directory opener found (open or xdg-open); browse #{path} manually"
      return
    end
    warn "Could not open #{path}; the bucket remains mounted" unless system(opener, path)
  end

  def mount(profile, env = ENV)
    path = mountpoint(profile)
    endpoint, bucket, key, secret = configuration(env)
    if mounted?(path)
      options, status = Open3.capture2('findmnt', '-n', '-o', 'OPTIONS', '--target', path)
      unless status.success? && options.strip.split(',').include?('rw')
        raise "Existing mount is not read-write; run rake minio-unmount[#{profile}] first"
      end
      puts "Already mounted read-write at #{path}"
      open_directory(path)
      return
    end
    FileUtils.mkdir_p(path)
    credentials = {
      'AWS_ACCESS_KEY_ID' => key, 'AWS_SECRET_ACCESS_KEY' => secret,
      'AWS_SESSION_TOKEN' => nil, 'AWS_PROFILE' => nil
    }
    # Credentials go only into the child environment, never argv or a file.
    _stdout, _stderr, status = Open3.capture3(credentials, 's3fs', bucket, path,
      '-o', 'rw', '-o', 'use_path_request_style', '-o', "url=#{endpoint}",
      '-o', 'connect_timeout=10', '-o', 'readwrite_timeout=30')
    raise 's3fs failed to start; check the profile credentials and S3 endpoint' unless status.success?
    # s3fs checks authentication in its daemon before the mount becomes ready.
    30.times do
      if mounted?(path)
        puts "Mounted read-write at #{path}"
        open_directory(path)
        return
      end
      sleep 0.2
    end
    raise 'MinIO mount failed; check connectivity/credentials with journalctl -t s3fs'
  rescue Errno::ENOENT
    raise 'Install s3fs and FUSE mount tools first (Ubuntu: sudo apt install s3fs)'
  end

  def unmount(profile)
    path = mountpoint(profile)
    unless mounted?(path)
      puts "Not mounted: #{path}"
      return
    end
    _stdout, _stderr, status = Open3.capture3('fusermount', '-u', '--', path)
    raise "Could not unmount #{path}; close files or terminals using the mount and retry" unless status.success?
    puts "Unmounted #{path}"
  rescue Errno::ENOENT
    raise 'Install the FUSE unmount tool first (Ubuntu: sudo apt install s3fs)'
  end
end
